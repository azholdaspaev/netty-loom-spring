#!/usr/bin/env python3
"""Tests for summarize.py's validity gate — what it will and will not publish as a measurement.

Run:  python3 scripts/test-summarize.py

Stdlib only, and summarize.py is driven as the script it is (argv in, Markdown out) rather than
imported: it reads sys.argv at module scope, and the rendered snapshot is the thing under test.

The fixtures matter more than usual here. A k6 run that aborts mid-flight still writes a complete-
looking --summary-export, and its check rate is perfect by construction, because checks are only
evaluated on requests that were actually issued. The truncated summaries below are therefore built
to be indistinguishable from healthy ones — that is the defect these tests pin.
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SUMMARIZE = os.path.join(SCRIPT_DIR, "summarize.py")

# k6 exit codes, verified against k6 v1.4.2. An abort outranks a threshold breach: a test that
# aborts *and* misses a threshold exits 108, not 99, so 99 unambiguously means "ran to the end".
K6_OK = 0
K6_THRESHOLDS_CROSSED = 99
K6_SCRIPT_ABORTED = 108

VUS = 2000
# Distinct per target and scenario so an assertion can tell whose number leaked into the snapshot.
HEALTHY = {
    ("netty-loom", "low"): 12000.0,
    ("netty-loom", "high"): 30885.0,
    ("netty-loom", "secured"): 23296.0,
    ("tomcat-platform", "low"): 11000.0,
    ("tomcat-platform", "high"): 3610.0,
    ("tomcat-platform", "secured"): 2602.0,
    ("tomcat-virtual", "low"): 11500.0,
    ("tomcat-virtual", "high"): 30128.0,
    ("tomcat-virtual", "secured"): 22208.0,
}
LABELS = {
    "netty-loom": "Netty-Loom (this library)",
    "tomcat-platform": "Tomcat, platform threads",
    "tomcat-virtual": "Tomcat, virtual threads",
}


def k6_summary(count, rate, p99, check_fails=0):
    """A --summary-export in the shape k6 v1.4.2 writes, tagged and untagged sub-metrics alike."""
    trend = {"avg": p99 / 2, "min": 1.0, "med": p99 / 3, "p(50)": p99 / 3,
             "p(90)": p99 * 0.9, "p(95)": p99 * 0.95, "p(99)": p99, "max": p99}
    reqs = {"count": count, "rate": rate}
    failed = {"passes": 0, "fails": count, "value": 0.0}
    checks = {"passes": count - check_fails, "fails": check_fails, "value": 1}
    return {"metrics": {
        "http_reqs": dict(reqs), "http_reqs{phase:work}": dict(reqs),
        "http_req_duration": dict(trend), "http_req_duration{phase:work}": dict(trend),
        "http_req_failed": dict(failed), "http_req_failed{phase:work}": dict(failed),
        "checks": checks,
    }}


def section(markdown, heading_prefix):
    """The lines of the `## ` section whose heading starts with `heading_prefix`."""
    lines = markdown.splitlines()
    for i, line in enumerate(lines):
        if line.startswith("## " + heading_prefix):
            rest = lines[i + 1:]
            end = next((j for j, l in enumerate(rest) if l.startswith("## ")), len(rest))
            return rest[:end]
    raise AssertionError(f"no section starting {heading_prefix!r} in:\n{markdown}")


def table_row(markdown, heading_prefix, target):
    """The one table row for `target` inside the named section."""
    prefix = "| " + LABELS[target] + " |"
    rows = [l for l in section(markdown, heading_prefix) if l.startswith(prefix)]
    assert len(rows) == 1, f"expected one {target!r} row under {heading_prefix!r}, got {rows}"
    return rows[0]


class SummarizeTest(unittest.TestCase):
    """Each test renders a whole sweep, healthy except for the runs it names."""

    SCENARIO_1 = "Scenario 1"
    SCENARIO_2 = "Scenario 2"
    SCENARIO_3 = "Scenario 3"
    SECURITY = "Security overhead"
    CPU = "CPU efficiency"
    MEMORY = "Memory per connection"

    def render(self, runs=None):
        """Render a snapshot. `runs` overrides individual (target, scenario) pairs with a dict of
        `exit` / `count` / `rate` / `check_fails`; `exit=None` writes no .exit file at all."""
        runs = runs or {}
        with tempfile.TemporaryDirectory() as results:
            for target in LABELS:
                for scenario in ("low", "high", "secured"):
                    spec = runs.get((target, scenario), {})
                    rate = spec.get("rate", HEALTHY[(target, scenario)])
                    count = spec.get("count", 100000)
                    summary = k6_summary(count, rate, spec.get("p99", 100.0),
                                         spec.get("check_fails", 0))
                    with open(os.path.join(results, f"{target}_{scenario}.summary.json"), "w") as f:
                        json.dump(summary, f)
                    code = spec.get("exit", K6_OK)
                    if code is not None:
                        with open(os.path.join(results, f"{target}_{scenario}.exit"), "w") as f:
                            f.write(f"{code}\n")
                self._write_samples(results, target)
            proc = subprocess.run(
                [sys.executable, SUMMARIZE, results, str(VUS), "-Xmx2g", "test-box", "4000"],
                capture_output=True, text=True, check=True)
        return proc.stdout

    def _write_samples(self, results, target):
        """Idle and under-load memory/CPU samples, in sample-memory.sh's CSV shape."""
        for name, rss, cpu in ((f"{target}_idle.csv", 400000, 10),
                               (f"{target}_high_load.csv", 900000, 60)):
            with open(os.path.join(results, name), "w") as f:
                f.write("ts_epoch,rss_kb,heap_used_kb,cpu_cumulative\n")
                f.write(f"1000,{rss},200000,0:{cpu:02d}.00\n")
                f.write(f"1060,{rss + 1000},201000,0:{cpu + 30:02d}.00\n")

    # ---- the defect in #110 ----

    def test_aborted_secured_run_is_not_published(self):
        """A secured run killed by exec.test.abort() after 1,660 requests is not a measurement.

        This is #110 exactly: non-zero request count, zero failed checks, so the pre-fix gate saw
        a healthy run and published 55 req/s.
        """
        md = self.render({("tomcat-virtual", "secured"):
                          {"exit": K6_SCRIPT_ABORTED, "count": 1660, "rate": 55.0, "p99": 18468.0}})
        row = table_row(md, self.SCENARIO_3, "tomcat-virtual")
        self.assertEqual(row.count("invalid"), 5, row)
        self.assertNotIn("55", row)
        self.assertNotIn("18468", md)

    def test_aborted_run_is_refused_outside_the_secured_scenario(self):
        """The gate covers every scenario, not only the one that happened to have a gate."""
        md = self.render({("netty-loom", "high"):
                          {"exit": K6_SCRIPT_ABORTED, "count": 96, "rate": 3.0}})
        self.assertEqual(table_row(md, self.SCENARIO_2, "netty-loom").count("invalid"), 5)
        # CPU and memory are per-plateau figures; without a plateau they are arithmetic, not data.
        self.assertIn("invalid", table_row(md, self.CPU, "netty-loom"))
        self.assertIn("invalid", table_row(md, self.MEMORY, "netty-loom"))

    def test_missing_exit_file_is_refused(self):
        """No record that the run finished is not a record that it did — fail closed."""
        md = self.render({("netty-loom", "secured"): {"exit": None}})
        self.assertEqual(table_row(md, self.SCENARIO_3, "netty-loom").count("invalid"), 5)

    def test_a_crossed_threshold_still_publishes(self):
        """The over-gating guard: exit 99 is the saturated-target finding this harness exists for.

        `tomcat-platform` breaches the error-rate threshold at high VU counts on every real sweep.
        Refusing it would delete the comparison rather than protect it.
        """
        md = self.render({("tomcat-platform", "high"):
                          {"exit": K6_THRESHOLDS_CROSSED, "rate": 3610.0}})
        row = table_row(md, self.SCENARIO_2, "tomcat-platform")
        self.assertNotIn("invalid", row)
        self.assertIn("3610", row)
        self.assertNotIn("invalid", table_row(md, self.MEMORY, "tomcat-platform"))

    def test_verdict_refuses_rather_than_comparing_the_survivors(self):
        """A comparative claim needs the whole field, not whichever targets happened to finish.

        Pre-fix, `max()` over the surviving Tomcat rows produced the false sentence quoted in
        docs/benchmarks/2026-08-01/COMPARISON.md §4.
        """
        md = self.render({
            ("netty-loom", "secured"): {"rate": 100.0},
            ("tomcat-platform", "secured"): {"rate": 30000.0},
            ("tomcat-virtual", "secured"): {"exit": K6_SCRIPT_ABORTED, "count": 1660, "rate": 55.0},
        })
        security = "\n".join(section(md, self.SECURITY))
        self.assertIn("Not answerable", security)
        self.assertNotIn("the filter chain costs this stack more than it costs Tomcat", security)

    def test_a_whole_sweep_of_aborted_secured_runs_still_states_it_could_not_answer(self):
        """The section must not vanish when *every* secured run is refused.

        A missing section is the same silent omission as a missing verdict: the reader cannot tell
        a question that was refused from one that was never asked.
        """
        aborted = {"exit": K6_SCRIPT_ABORTED, "count": 1660, "rate": 55.0}
        md = self.render({(t, "secured"): dict(aborted) for t in LABELS})
        security = "\n".join(section(md, self.SECURITY))
        self.assertEqual(security.count("Not answerable"), 2, security)
        for target in LABELS:
            row = table_row(md, self.SECURITY, target)
            # The `/work` pair still publishes -- scenario 2 completed. Its four secured cells
            # (throughput, Δ throughput, p99, Δ p99) must say `invalid`, the same word the
            # scenario-3 table uses, rather than `n/a` -- which would read as merely absent.
            self.assertEqual(row.count("invalid"), 4, row)
            self.assertNotIn("n/a", row)


if __name__ == "__main__":
    unittest.main(verbosity=2)
