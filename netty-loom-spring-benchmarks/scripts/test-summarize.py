#!/usr/bin/env python3
"""Tests for summarize.py's validity gate — what it will and will not publish as a measurement.

Run:  python3 scripts/test-summarize.py

Stdlib only, and summarize.py is driven as the script it is (argv in, Markdown out) rather than
imported: it reads sys.argv at module scope, and the rendered snapshot is the thing under test.

The fixtures matter more than usual here: the truncated runs below are built to be indistinguishable
from healthy ones, which is the defect these tests pin. See summarize.py's K6_THRESHOLDS_CROSSED for
why nothing inside an export gives the truncation away.
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

    HEADLINE = "Headline"
    SCENARIO_1 = "Scenario 1"
    SCENARIO_2 = "Scenario 2"
    SCENARIO_3 = "Scenario 3"
    SECURITY = "Security overhead"
    CPU = "CPU efficiency"
    MEMORY = "Memory per connection"

    def render(self, runs=None):
        """Render a snapshot. `runs` overrides individual (target, scenario) pairs with a dict of
        `exit` / `count` / `rate` / `check_fails`; `exit=None` writes no .exit file at all,
        `no_export=True` writes no summary export -- the shape of a run that died before k6 could
        write one -- `empty_export=True` writes `{}`, and `drop_metrics` omits named metrics, the
        shape of a tagged sub-metric no threshold asked k6 to emit."""
        runs = runs or {}
        with tempfile.TemporaryDirectory() as results:
            for target in LABELS:
                for scenario in ("low", "high", "secured"):
                    spec = runs.get((target, scenario), {})
                    rate = spec.get("rate", HEALTHY[(target, scenario)])
                    count = spec.get("count", 100000)
                    summary = k6_summary(count, rate, spec.get("p99", 100.0),
                                         spec.get("check_fails", 0))
                    for name in spec.get("drop_metrics", ()):
                        summary["metrics"].pop(name, None)
                    if not spec.get("no_export"):
                        with open(os.path.join(results,
                                               f"{target}_{scenario}.summary.json"), "w") as f:
                            json.dump({} if spec.get("empty_export") else summary, f)
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

    def test_aborted_high_run_is_refused_along_with_its_derived_tables(self):
        """Scenario 2 had no gate at all before this branch, and two tables read its numbers."""
        md = self.render({("netty-loom", "high"):
                          {"exit": K6_SCRIPT_ABORTED, "count": 96, "rate": 3.0}})
        self.assertEqual(table_row(md, self.SCENARIO_2, "netty-loom").count("invalid"), 5)
        # CPU and memory are per-plateau figures; without a plateau they are arithmetic, not data.
        self.assertIn("invalid", table_row(md, self.CPU, "netty-loom"))
        self.assertIn("invalid", table_row(md, self.MEMORY, "netty-loom"))

    def test_aborted_low_run_is_refused(self):
        """Scenario 1 too — "every scenario" is the title's claim, so each one is exercised.

        Only `low` reaches the gate through a code path no other test takes: it is the one
        scenario with neither a secured clause nor a derived table to fail alongside it.
        """
        md = self.render({("tomcat-virtual", "low"):
                          {"exit": K6_SCRIPT_ABORTED, "count": 42, "rate": 7.0}})
        row = table_row(md, self.SCENARIO_1, "tomcat-virtual")
        self.assertEqual(row.count("invalid"), 5, row)
        self.assertNotIn("11500", md)
        # Scenario 1 is nobody else's input: the other two targets and scenarios are untouched.
        self.assertNotIn("invalid", table_row(md, self.SCENARIO_2, "tomcat-virtual"))
        self.assertNotIn("invalid", table_row(md, self.SCENARIO_1, "netty-loom"))

    def test_a_clean_exit_beside_no_export_is_refused(self):
        """An exit code is evidence about a run, not a substitute for its measurements.

        This is what a fresh clone looked like once the .exit files were committable: nine files
        saying `0`, no exports, no CSVs. Every cell had nothing behind it, yet `err` was computed
        outside the gate and `or 0` turned *absent* into *measured zero*, so the most flattering
        number in the table was the one published.
        """
        md = self.render({(t, s): {"exit": K6_OK, "no_export": True}
                          for t in LABELS for s in ("low", "high", "secured")})
        for heading in (self.SCENARIO_1, self.SCENARIO_2, self.SCENARIO_3):
            row = table_row(md, heading, "netty-loom")
            self.assertEqual(row.count("invalid"), 5, row)
            self.assertNotIn("0.00%", row)
        self.assertIn("invalid", table_row(md, self.MEMORY, "netty-loom"))

    def test_a_missing_export_does_not_reach_the_derived_tables(self):
        """One target's scenario-2 export gone, its .exit clean — the memory row published
        250.00 KB/connection for a run with no k6 data at all."""
        md = self.render({("netty-loom", "high"): {"exit": K6_OK, "no_export": True}})
        self.assertEqual(table_row(md, self.SCENARIO_2, "netty-loom").count("invalid"), 5)
        self.assertIn("invalid", table_row(md, self.MEMORY, "netty-loom"))
        self.assertIn("invalid", table_row(md, self.CPU, "netty-loom"))

    def test_an_export_carrying_no_request_metric_is_refused(self):
        """An export that parsed but recorded nothing for this scenario is not a measurement.

        Reachable with a clean exit: every VU dies in setup, k6 writes an export, and none of the
        http_req_* metrics exist. `pick(reqs, "count")` is then `None`, and `None != 0` is true.
        """
        md = self.render({("netty-loom", "secured"): {"exit": K6_OK, "empty_export": True}})
        self.assertEqual(table_row(md, self.SCENARIO_3, "netty-loom").count("invalid"), 5)

    def test_an_absent_error_rate_is_not_reported_as_zero(self):
        """`or 0` made *absent* into *measured zero* — the most flattering number available.

        The tagged sub-metrics exist only because a threshold names them (see the `thresholds`
        block in high-concurrency-secured.js), so dropping one is a live shape, not a contrivance.
        """
        md = self.render({("netty-loom", "secured"):
                          {"drop_metrics": ["http_req_failed{phase:work}"]}})
        row = table_row(md, self.SCENARIO_3, "netty-loom")
        self.assertNotIn("0.00%", row)
        self.assertIn("n/a", row)

    def test_the_headline_needs_the_error_rate_it_quotes(self):
        """It states throughput, p99 *and* error rate, so all three gate it."""
        md = self.render({(t, "high"): {"drop_metrics": ["http_req_failed"]} for t in LABELS})
        self.assertIn("Not answerable", "\n".join(section(md, self.HEADLINE)))

    def test_missing_exit_file_is_refused(self):
        """No record that the run finished is not a record that it did — fail closed."""
        md = self.render({("netty-loom", "secured"): {"exit": None}})
        self.assertEqual(table_row(md, self.SCENARIO_3, "netty-loom").count("invalid"), 5)

    # ---- the older clause beside it: secured-only, and orthogonal to the exit code ----

    def test_a_cleanly_exited_secured_run_with_failed_checks_is_refused(self):
        """The unauthenticated-302 fake win, which the exit-code gate cannot see.

        Every request becomes a cheap redirect to /login, which k6 counts as a *successful*
        response — higher throughput, lower latency, 0% transport errors — and the run exits 0.
        """
        md = self.render({("tomcat-virtual", "secured"):
                          {"exit": K6_OK, "check_fails": 5000, "rate": 99999.0}})
        row = table_row(md, self.SCENARIO_3, "tomcat-virtual")
        self.assertEqual(row.count("invalid"), 5, row)
        self.assertNotIn("99999", md)

    def test_a_cleanly_exited_secured_run_with_no_steady_state_requests_is_refused(self):
        """Zero tagged work requests: every VU stalled in the login ramp, threshold crossed, exit 99.

        The exit-code gate publishes 99 by design, so this row survives it and only the request
        count refuses it.
        """
        md = self.render({("netty-loom", "secured"):
                          {"exit": K6_THRESHOLDS_CROSSED, "count": 0, "rate": 0.0}})
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
        self.assertNotIn("Not answerable", "\n".join(section(md, self.HEADLINE)))

    def test_a_failed_check_outside_the_secured_scenario_still_publishes(self):
        """The scoping guard: the checks clause is secured-only, and that is a decision.

        In scenarios 1 and 2 a failed check means the server returned non-200 under load, which is
        the finding the error-rate column exists to report. Without this, deleting
        `scenario != SECURED_SCENARIO` — so the clause refuses everywhere — went unnoticed.
        """
        md = self.render({("tomcat-platform", "high"):
                          {"exit": K6_OK, "check_fails": 5000, "rate": 3610.0}})
        row = table_row(md, self.SCENARIO_2, "tomcat-platform")
        self.assertNotIn("invalid", row)
        self.assertIn("3610", row)

    def test_headline_states_its_refusal_instead_of_vanishing(self):
        """The most-read section of the snapshot is not exempt from saying it was refused.

        `nl`/`tp`/`tv` now come from the gated `high_stats()`, a state scenario 2 could not reach
        before the completion gate, so the guard that skipped a headline for want of data became a
        guard that deletes the headline, both verdicts and the single-box caveat without a word.
        """
        md = self.render({("netty-loom", "high"):
                          {"exit": K6_SCRIPT_ABORTED, "count": 96, "rate": 3.0}})
        headline = "\n".join(section(md, self.HEADLINE))
        self.assertIn("Not answerable", headline)
        self.assertIn(LABELS["netty-loom"], headline)
        # Only the target actually refused is named as the reason.
        self.assertNotIn(LABELS["tomcat-virtual"], headline)

    def test_the_headline_refusal_lists_several_targets_readably(self):
        """Two of the three labels contain commas, so `, `.join renders three targets as five.

        Only ever refusing one target is what let that through, and an all-refused sweep is not
        exotic — it is what the README describes for a results directory predating the gate.
        """
        md = self.render({(t, "high"): {"exit": K6_SCRIPT_ABORTED} for t in LABELS})
        headline = "\n".join(section(md, self.HEADLINE))
        self.assertIn("; ".join(LABELS[t] for t, _ in (("netty-loom", 0), ("tomcat-platform", 0),
                                                       ("tomcat-virtual", 0))), headline)
        # No claim about a "rest" that does not exist, and none about a "why" the table never states.
        self.assertNotIn("the rest", headline)
        self.assertNotIn("why", headline)

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

    def test_a_secured_run_that_wrote_no_export_still_reports_the_attempt(self):
        """An attempt is recorded by the .exit file, which run-all.sh writes unconditionally.

        A run that dies early enough -- a script exception (107), or k6 missing from PATH (127) --
        never writes a summary export at all. Deciding "was this attempted?" from the export alone
        drops the whole section, so the snapshot never mentions the comparison was tried.
        """
        md = self.render({(t, "secured"): {"exit": 107, "no_export": True} for t in LABELS})
        security = "\n".join(section(md, self.SECURITY))
        self.assertEqual(security.count("Not answerable"), 2, security)
        for target in LABELS:
            self.assertEqual(table_row(md, self.SECURITY, target).count("invalid"), 4)

    def test_a_sweep_that_never_ran_the_secured_scenario_omits_the_section(self):
        """The other half of the same decision: nothing attempted, nothing to refuse."""
        md = self.render({(t, "secured"): {"exit": None, "no_export": True} for t in LABELS})
        self.assertNotIn("## Security overhead", md)
        self.assertIn("## Scenario 2", md)

    def test_the_refusal_does_not_diagnose_a_cause_it_cannot_know(self):
        """`thr is None` has two causes, and only one of them is an unfinished run.

        The secured checks gate fires on runs that exited *cleanly* — the unauthenticated-302 case
        it exists for is an exit 0. Telling that reader the run "did not complete" sends them
        looking for a truncated run and finding a clean exit, which is the same wrong-diagnosis
        cost this branch is closing, pointed the other way.
        """
        md = self.render({("tomcat-platform", "secured"): {"exit": K6_OK, "check_fails": 5000}})
        security = "\n".join(section(md, self.SECURITY))
        self.assertIn("Not answerable", security)
        self.assertNotIn("did not complete", security)

    def test_a_measured_zero_baseline_is_not_called_unpublishable(self):
        """`not theirs` guards the division, but it shared the refusal branch's words.

        A target that starts and serves nothing crosses the error-rate threshold, exits 99, and is
        published as `0` req/s — scenario 2 has no request-count gate, that clause is secured-only.
        The verdict then called a run unpublishable while the tables above published it.
        """
        md = self.render({("tomcat-virtual", "high"): {"exit": K6_THRESHOLDS_CROSSED, "rate": 0.0}})
        security = "\n".join(section(md, self.SECURITY))
        # The row is published, so the refusal wording must not be attached to it.
        self.assertNotIn("invalid", table_row(md, self.SCENARIO_2, "tomcat-virtual"))
        self.assertNotIn("is not publishable", security)
        self.assertIn("0 req/s", security)

    def test_an_empty_export_counts_as_an_attempt(self):
        """A written-but-empty export is a run that happened, so `is not None`, not truthiness.

        Pairs with a pre-gate results directory, which is the case that keeps the export in the
        disjunction at all: exports present, no .exit files, and it must still render as refused.
        """
        md = self.render({(t, "secured"): {"exit": None, "empty_export": True} for t in LABELS})
        self.assertIn("## Security overhead", md)
        self.assertEqual("\n".join(section(md, self.SECURITY)).count("Not answerable"), 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
