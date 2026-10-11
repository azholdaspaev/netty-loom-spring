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
    ("netty-loom", "memory"): 30001.0,
    ("tomcat-platform", "memory"): 3601.0,
    ("tomcat-virtual", "memory"): 30101.0,
}
SCENARIOS = ("low", "high", "secured", "memory")
AFTER_CLOSE_LIVE = 17825792
PRELOAD_OTHER_KB = 5000
PLATEAU_VTHREADS = [655, 1500, 2241, 3000, 4112]
LABELS = {
    "netty-loom": "Netty-Loom (this library)",
    "tomcat-platform": "Tomcat, platform threads",
    "tomcat-virtual": "Tomcat, virtual threads",
}


def k6_summary(count, rate, p99, check_fails=0, login_crossed=False):
    """A --summary-export in the shape k6 v1.4.2 writes, tagged and untagged sub-metrics alike.

    Note k6 records a threshold's verdict inverted: `true` means it was *crossed*, not that it held.
    """
    trend = {"avg": p99 / 2, "min": 1.0, "med": p99 / 3, "p(50)": p99 / 3,
             "p(90)": p99 * 0.9, "p(95)": p99 * 0.95, "p(99)": p99, "max": p99}
    reqs = {"count": count, "rate": rate}
    failed = {"passes": 0, "fails": count, "value": 0.0}
    checks = {"passes": count - check_fails, "fails": check_fails, "value": 1}
    login = {"passes": 0, "fails": VUS, "value": 0.05 if login_crossed else 0.0,
             "thresholds": {"rate<0.01": login_crossed}}
    return {"metrics": {
        "http_reqs": dict(reqs), "http_reqs{phase:work}": dict(reqs),
        "http_req_duration": dict(trend), "http_req_duration{phase:work}": dict(trend),
        "http_req_failed": dict(failed), "http_req_failed{phase:work}": dict(failed),
        "checks": checks, "login_failed": login,
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


def cells(row):
    """A Markdown table row's cells, label first."""
    return [c.strip() for c in row.strip().strip("|").split("|")]


class SummarizeTest(unittest.TestCase):
    """Each test renders a whole sweep, healthy except for the runs it names."""

    HEADLINE = "Headline"
    SCENARIO_1 = "Scenario 1"
    SCENARIO_2 = "Scenario 2"
    SCENARIO_3 = "Scenario 3"
    SECURITY = "Security overhead"
    CPU = "CPU efficiency"
    MEMORY = "Memory per connection"

    def render(self, runs=None, env=None, env_path=None, retained=None):
        """Render a snapshot, asserting summarize.py accepted the sweep.

        `runs` overrides individual (target, scenario) pairs with a dict of
        `exit` / `count` / `rate` / `check_fails`; `exit=None` writes no .exit file at all,
        `no_export=True` writes no summary export -- the shape of a run that died before k6 could
        write one -- `empty_export=True` writes `{}`, and `drop_metrics` omits named metrics, the
        shape of a tagged sub-metric no threshold asked k6 to emit. `env` writes an environment
        capture and names it to summarize.py; `env_path` names one without writing it. `retained`
        overrides a target's sample-live-heap.sh captures: `vthreads` and `live` replace the plateau
        columns, `no_other` blanks the NMT column in "preload", "plateau" or
        "both", `no_plateau` writes no plateau capture."""
        proc = self._summarize(runs, env, env_path, retained)
        self.assertEqual(0, proc.returncode, proc.stderr)
        return proc.stdout

    def render_refused(self, runs=None, env=None, env_path=None):
        """Render a snapshot that summarize.py must refuse outright, returning its stderr."""
        proc = self._summarize(runs, env, env_path)
        self.assertNotEqual(0, proc.returncode, "expected a refusal, got a snapshot")
        return proc.stderr

    def _summarize(self, runs=None, env=None, env_path=None, retained=None):
        runs = runs or {}
        retained = retained or {}
        with tempfile.TemporaryDirectory() as results:
            for target in LABELS:
                for scenario in SCENARIOS:
                    spec = runs.get((target, scenario), {})
                    rate = spec.get("rate", HEALTHY[(target, scenario)])
                    count = spec.get("count", 100000)
                    summary = k6_summary(count, rate, spec.get("p99", 100.0),
                                         spec.get("check_fails", 0),
                                         spec.get("login_crossed", False))
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
                self._write_retained(results, target, retained.get(target, {}))
            argv = [sys.executable, SUMMARIZE, results, str(VUS), "-Xmx2g", "test-box", "4000"]
            if env is not None:
                env_path = os.path.join(results, "env-server.txt")
                with open(env_path, "w") as f:
                    f.write(env)
            if env_path is not None:
                argv.append(env_path)
            return subprocess.run(argv, capture_output=True, text=True)

    def _write_samples(self, results, target):
        """Under-load CPU samples, in sample-memory.sh's CSV shape."""
        with open(os.path.join(results, f"{target}_high_load.csv"), "w") as f:
            f.write("ts_epoch,rss_kb,heap_used_kb,cpu_cumulative\n")
            f.write("1000,900000,200000,0:60.00\n")
            f.write("1060,901000,201000,0:90.00\n")

    def _write_retained(self, results, target, spec):
        """sample-live-heap.sh's three captures, sized so a connection retains 3 KiB of heap and
        1 KiB of pool growth, and an in-flight request 6.5 KiB."""
        vthreads = spec.get("vthreads", PLATEAU_VTHREADS)
        live = spec.get("live", [AFTER_CLOSE_LIVE + 3072 * VUS + 6656 * v for v in vthreads])
        other = "" if spec.get("no_other") in ("preload", "both") else PRELOAD_OTHER_KB
        plateau_other = "" if spec.get("no_other") in ("plateau", "both") else PRELOAD_OTHER_KB + VUS
        captures = {
            # Below after close: the run leaves state behind that no connection holds.
            "preload": [(AFTER_CLOSE_LIVE - 1048576, 0, other)],
            "after": [(AFTER_CLOSE_LIVE, 0, plateau_other)],
            "plateau": list(zip(live, vthreads, [plateau_other] * len(vthreads))),
        }
        if spec.get("no_plateau"):
            del captures["plateau"]
        for capture, samples in captures.items():
            with open(os.path.join(results, f"{target}_retained_{capture}.csv"), "w") as f:
                f.write("ts_epoch,live_bytes,virtual_threads,other_committed_kb\n")
                for i, (live_bytes, vts, other_kb) in enumerate(samples):
                    f.write(f"{1000 + i},{live_bytes},{vts},{other_kb}\n")

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

    def test_aborted_high_run_is_refused_along_with_its_cpu_row(self):
        """Scenario 2 had no gate at all before this branch, and the CPU table reads its numbers."""
        md = self.render({("netty-loom", "high"):
                          {"exit": K6_SCRIPT_ABORTED, "count": 96, "rate": 3.0}})
        self.assertEqual(table_row(md, self.SCENARIO_2, "netty-loom").count("invalid"), 5)
        # CPU is a per-plateau figure; without a plateau it is arithmetic, not data.
        self.assertIn("invalid", table_row(md, self.CPU, "netty-loom"))

    def test_aborted_low_run_is_refused(self):
        """Scenario 1 too — "every scenario" is the title's claim, so each one is exercised.

        Only `low` reaches the gate through a code path no other test takes: it is the one
        scenario with neither a secured clause nor a derived table to fail alongside it.
        """
        # A rate distinctive enough to assert on: the healthy baseline is never written for this
        # pair, so asserting its absence would pass under a fully-bypassed gate.
        md = self.render({("tomcat-virtual", "low"):
                          {"exit": K6_SCRIPT_ABORTED, "count": 42, "rate": 61803.0}})
        row = table_row(md, self.SCENARIO_1, "tomcat-virtual")
        self.assertEqual(row.count("invalid"), 5, row)
        self.assertNotIn("61803", md)
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
                          for t in LABELS for s in SCENARIOS})
        for heading in (self.SCENARIO_1, self.SCENARIO_2, self.SCENARIO_3):
            row = table_row(md, heading, "netty-loom")
            self.assertEqual(row.count("invalid"), 5, row)
            self.assertNotIn("0.00%", row)
        self.assertIn("invalid", table_row(md, self.MEMORY, "netty-loom"))

    def test_a_missing_export_does_not_reach_the_derived_tables(self):
        """One target's export gone, its .exit clean — the memory row once published
        250.00 KB/connection for a run with no k6 data at all."""
        md = self.render({("netty-loom", "high"): {"exit": K6_OK, "no_export": True},
                          ("netty-loom", "memory"): {"exit": K6_OK, "no_export": True}})
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

    def test_a_handful_of_failed_checks_does_not_refuse_the_secured_run(self):
        """Refuse to the tolerance the scenario itself declares, not to zero.

        `high-concurrency-secured.js` gates on `checks: ['rate>0.99']`, so refusing on a single
        failed check was stricter than the scenario's own threshold — and because the flagship
        verdict needs *both* Tomcat targets, three bad checks on the target designed to collapse
        under load silenced the comparison for both at once. The fake win this clause exists for
        fails ~100% of its checks, nowhere near this line.
        """
        md = self.render({("tomcat-platform", "secured"):
                          {"exit": K6_OK, "check_fails": 3, "rate": 2602.0}})
        row = table_row(md, self.SCENARIO_3, "tomcat-platform")
        self.assertNotIn("invalid", row)
        self.assertIn("2602", row)
        self.assertNotIn("Not answerable", "\n".join(section(md, self.SECURITY)))

    def test_a_secured_run_that_lost_too_many_logins_is_refused(self):
        """The plateau was served by fewer connections than the row claims, and nothing else shows it.

        A VU that cannot authenticate issues no steady-state requests at all, so every check still
        passes and the request count is still large: both the exit-code gate and the check gate see
        a healthy run. `high-concurrency-secured.js` declares the tolerance as `login_failed`, and a
        crossed threshold is the only trace left (#111).
        """
        md = self.render({("netty-loom", "secured"):
                          {"exit": K6_THRESHOLDS_CROSSED, "login_crossed": True, "rate": 44444.0}})
        row = table_row(md, self.SCENARIO_3, "netty-loom")
        self.assertEqual(row.count("invalid"), 5, row)
        self.assertNotIn("44444", md)

    def test_losing_a_login_within_the_tolerance_still_publishes(self):
        """One dead socket among thousands of VUs is weather, and the scenario says so by not
        crossing its own threshold. Refusing here would put the harness back where #111 found it."""
        md = self.render({("netty-loom", "secured"):
                          {"exit": K6_OK, "login_crossed": False, "rate": 23296.0}})
        row = table_row(md, self.SCENARIO_3, "netty-loom")
        self.assertNotIn("invalid", row)
        self.assertIn("23296", row)

    def test_an_export_predating_the_login_metric_still_publishes(self):
        """No login_failed metric means no threshold to inspect, not a refusal: results captured
        before the scenario declared one must still render."""
        md = self.render({("netty-loom", "secured"):
                          {"exit": K6_OK, "drop_metrics": ("login_failed",), "rate": 23296.0}})
        row = table_row(md, self.SCENARIO_3, "netty-loom")
        self.assertNotIn("invalid", row)
        self.assertIn("23296", row)

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


    # ---- memory per connection: retained state, read from the memory pass ----

    def test_memory_row_splits_retained_heap_by_in_flight_count(self):
        """The intercept of live heap against in-flight requests is what the connections retain.

        Every fixture plateau sample holds a different number of in-flight requests, as a real one
        does; reading any single sample would charge that sample's requests to the connections.
        """
        row = table_row(self.render(), self.MEMORY, "netty-loom")
        self.assertEqual(cells(row), [LABELS["netty-loom"], "655–4,112", "3.00", "1.00", "4.00",
                                      "6.50"])

    def test_memory_section_names_its_formula(self):
        text = "\n".join(section(self.render(), self.MEMORY))
        for term in ("GC.class_histogram", "java.lang.VirtualThread", "intercept", "Other"):
            self.assertIn(term, text)
        self.assertNotIn("RSS", text)

    def test_a_target_without_virtual_threads_publishes_its_median_live_heap(self):
        """tomcat-platform parks no virtual thread, so there is nothing to regress on.

        Refusing would drop the control target from every sweep; its in-flight state stays in
        the heap figure instead, and the slope it cannot have is `n/a`.
        """
        base = AFTER_CLOSE_LIVE + 3072 * VUS
        md = self.render(retained={"tomcat-platform": {
            "vthreads": [0] * 5, "live": [base - 4096, base, base + 4096, base, base + 8192]}})
        self.assertEqual(cells(table_row(md, self.MEMORY, "tomcat-platform")),
                         [LABELS["tomcat-platform"], "0–0", "3.00", "1.00", "4.00", "n/a"])

    def test_memory_without_native_memory_tracking_is_not_totalled(self):
        """NMT off leaves the pool growth unknown, and a heap-only total would read as the whole."""
        for blank in ("preload", "plateau", "both"):
            with self.subTest(blank=blank):
                md = self.render(retained={"netty-loom": {"no_other": blank}})
                self.assertEqual(cells(table_row(md, self.MEMORY, "netty-loom"))[2:],
                                 ["3.00", "n/a", "n/a", "6.50"])

    def test_memory_without_plateau_capture_is_not_available(self):
        """run-all.sh deletes a plateau capture that overlapped the ramp-down."""
        md = self.render(retained={"netty-loom": {"no_plateau": True}})
        self.assertEqual(cells(table_row(md, self.MEMORY, "netty-loom"))[1:], ["n/a"] * 5)

    def test_aborted_memory_pass_is_refused_while_scenario_2_publishes(self):
        md = self.render({("netty-loom", "memory"):
                          {"exit": K6_SCRIPT_ABORTED, "count": 96, "rate": 3.0}})
        self.assertEqual(table_row(md, self.MEMORY, "netty-loom").count("invalid"), 5)
        self.assertNotIn("invalid", table_row(md, self.SCENARIO_2, "netty-loom"))


    # ---- the environment capture ----

    def test_a_captured_environment_replaces_the_machine_line(self):
        md = self.render(env="- **Machine:** Xeon E3-1230 v3, 4 cores / 8 threads\n"
                             "- **Kernel:** 6.8.0-90-generic\n")
        self.assertIn("- **Machine:** Xeon E3-1230 v3, 4 cores / 8 threads", md)
        self.assertIn("- **Kernel:** 6.8.0-90-generic", md)
        self.assertNotIn("test-box", md)

    def test_no_environment_file_renders_the_machine_line_as_before(self):
        self.assertIn("- **Machine:** `test-box`", self.render())

    def test_a_named_environment_capture_that_is_missing_refuses_to_render(self):
        err = self.render_refused(env_path="/nonexistent/env-server.txt")
        self.assertIn("env-server.txt", err)

    def test_an_empty_environment_capture_is_refused(self):
        err = self.render_refused(env="\n  \n")
        self.assertIn("env-server.txt", err)


if __name__ == "__main__":
    unittest.main(verbosity=2)
