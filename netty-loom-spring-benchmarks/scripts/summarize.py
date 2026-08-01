#!/usr/bin/env python3
"""Render results/SNAPSHOT.md from the raw k6 summary exports and memory CSVs.

Reads, per target:
  <name>_low.summary.json      k6 --summary-export of the low-concurrency scenario
  <name>_high.summary.json     k6 --summary-export of the high-concurrency scenario
  <name>_secured.summary.json  k6 --summary-export of the secured high-concurrency scenario
  <name>_idle.csv              RSS samples taken before load
  <name>_high_load.csv         RSS samples taken during the high-concurrency run

Usage: summarize.py <results_dir> <vus> <java_flags> <uname> <tomcat_max_connections>
"""
import json
import os
import statistics
import sys
from datetime import date

RESULTS_DIR = sys.argv[1]
VUS = int(sys.argv[2])
JAVA_FLAGS = sys.argv[3]
UNAME = sys.argv[4] if len(sys.argv) > 4 else "unknown"
TOMCAT_MAXCONN = sys.argv[5] if len(sys.argv) > 5 else None
NCORES = os.cpu_count() or 1

TARGETS = [
    ("netty-loom", "Netty-Loom (this library)"),
    ("tomcat-platform", "Tomcat, platform threads"),
    ("tomcat-virtual", "Tomcat, virtual threads"),
]

# The secured scenario tags its steady-state requests so the per-VU logins, which all land in the
# ramp, stay out of the reading. k6 only exports a tagged sub-metric when a threshold names it.
SECURED_SCENARIO = "secured"
SECURED_SELECTOR = "{phase:work}"

# Observed run-to-run spread on this single-box harness. Used to stop the scripted verdicts below
# from calling a winner on a gap that is indistinguishable from noise.
NOISE_FLOOR_PCT = 11

_summary_cache = {}


def load_summary(name, scenario):
    key = (name, scenario)
    if key not in _summary_cache:
        path = os.path.join(RESULTS_DIR, f"{name}_{scenario}.summary.json")
        result = None
        if os.path.exists(path):
            with open(path) as f:
                try:
                    result = json.load(f)
                except json.JSONDecodeError:
                    result = None
        _summary_cache[key] = result
    return _summary_cache[key]


def metric(summary, mname, selector=""):
    """The named metric, or its tagged sub-metric when `selector` is given.

    No fallback to the untagged metric on a miss: the untagged one includes every VU's login
    requests, so substituting it would publish ramp traffic as the authenticated steady state.
    Missing means `n/a`.
    """
    if not summary:
        return {}
    return summary.get("metrics", {}).get(mname + selector, {})


def pick(d, *keys):
    for k in keys:
        if k in d and d[k] is not None:
            return d[k]
    return None


def rss_kb(csv_name):
    path = os.path.join(RESULTS_DIR, csv_name)
    vals = []
    if not os.path.exists(path):
        return vals
    with open(path) as f:
        next(f, None)
        for line in f:
            parts = line.strip().split(",")
            if len(parts) >= 2 and parts[1].isdigit():
                vals.append(int(parts[1]))
    return vals


def parse_cputime(s):
    """Parse `ps -o cputime` output ([DD-]HH:MM:SS[.ss] / MM:SS.ss) to seconds."""
    s = s.strip()
    if not s:
        return None
    days = 0
    if "-" in s:
        d, s = s.split("-", 1)
        days = int(d)
    try:
        parts = [float(p) for p in s.split(":")]
    except ValueError:
        return None
    while len(parts) < 3:
        parts.insert(0, 0.0)
    h, m, sec = parts
    return days * 86400 + h * 3600 + m * 60 + sec


def cpu_avg_cores(csv_name):
    """Average cores used over the sampling window = Δ(cumulative CPU time) / Δ(wall clock).

    Differencing first/last valid samples cancels CPU burned before the window (startup, JIT).
    """
    path = os.path.join(RESULTS_DIR, csv_name)
    if not os.path.exists(path):
        return None
    samples = []
    with open(path) as f:
        next(f, None)
        for line in f:
            parts = line.strip().split(",")
            if len(parts) >= 4 and parts[0].isdigit():
                cpu = parse_cputime(parts[3])
                if cpu is not None:
                    samples.append((int(parts[0]), cpu))
    if len(samples) < 2:
        return None
    wall = samples[-1][0] - samples[0][0]
    cpu = samples[-1][1] - samples[0][1]
    return (cpu / wall) if wall > 0 else None


def f(v, nd=1, suffix=""):
    return "n/a" if v is None else f"{v:.{nd}f}{suffix}"


def delta(before, after):
    """`after` as a signed percentage change from `before`; the column header says which direction
    is better."""
    if not before or after is None:
        return "n/a"
    change = (after - before) / before * 100
    return f"{change:+.1f}%"


def mb(kb):
    return None if kb is None else kb / 1024.0


def row(cells):
    return "| " + " | ".join(cells) + " |"


out = []
out.append("# Benchmark snapshot — Netty-Loom vs Tomcat")
out.append("")
out.append(f"- **Date:** {date.today().isoformat()}")
out.append(f"- **Machine:** `{UNAME}`")
out.append(f"- **JVM flags (identical for all targets):** `{JAVA_FLAGS}`")
out.append(f"- **Logical cores:** {NCORES} (client and server share this box — see CPU efficiency below)")
if TOMCAT_MAXCONN:
    out.append(f"- **Tomcat connector:** `max-connections={TOMCAT_MAXCONN}` on both Tomcat targets, "
               f"and `threads.max={TOMCAT_MAXCONN}` on the virtual target (platform keeps the default "
               "`threads.max=200` — the thread-per-request pool under test). Raised above the connection "
               "count so Tomcat's default `max-connections=8192` accept ceiling — which "
               "`spring.threads.virtual.enabled` does not touch — isn't the confound.")
out.append(f"- **High-concurrency connections (VUs):** {VUS:,}")
out.append("- **Workload:** `GET /work` → `Thread.sleep(50)` (simulated 50ms blocking DB call), and "
           "`GET /work-secured` → the same sleep behind `formLogin()`; keep-alive on, so 1 VU ≈ 1 "
           "connection ≈ 1 in-flight request.")
out.append("")
out.append("> Generated by `scripts/run-all.sh`. Raw k6 exports and memory CSVs live beside this file "
           "(git-ignored). Re-run to regenerate.")
out.append("")

# ---- Headline (derived from scenario 2 data) ----
def scenario_stats(name, scenario, selector=""):
    """Throughput and tail latency for one target's run of one scenario.

    The secured scenario -- and only it -- reports nothing when its correctness checks failed. An
    unauthenticated run answers every request with a cheap 302, which k6 counts as a *successful*
    response, so error rate reads 0.00% while throughput and p99 are pure fiction. Same for a run
    that aborted before issuing a steady-state request: k6 still writes a summary export, with
    zeroed counters. Scenarios 1 and 2 are deliberately NOT gated this way -- there a failed check
    means the server returned non-200 under load, which is the finding the error-rate column exists
    to report, not a reason to suppress the row.
    """
    s = load_summary(name, scenario)
    reqs = metric(s, "http_reqs", selector)
    valid = scenario != SECURED_SCENARIO or (
        not metric(s, "checks").get("fails") and pick(reqs, "count") != 0)
    return {
        "thr": pick(reqs, "rate") if valid else None,
        "p99": pick(metric(s, "http_req_duration", selector), "p(99)") if valid else None,
        "err": (pick(metric(s, "http_req_failed", selector), "value", "rate") or 0) * 100,
        "valid": valid,
    }


def high_stats(name):
    stats = scenario_stats(name, "high")
    cores = cpu_avg_cores(f"{name}_high_load.csv")
    thr = stats["thr"]
    return stats | {
        "cores": cores,
        "per_core": (thr / cores) if (thr is not None and cores) else None,
    }


nl = high_stats("netty-loom")
tp = high_stats("tomcat-platform")
tv = high_stats("tomcat-virtual")
if all(v["thr"] is not None and v["p99"] is not None for v in (nl, tp, tv)):
    out.append("## Headline")
    out.append("")
    out.append(f"At **{VUS:,} concurrent blocking connections**:")
    out.append("")
    out.append(f"- **Throughput:** Netty-Loom {nl['thr']:,.0f} req/s vs Tomcat+VT {tv['thr']:,.0f} "
               f"vs Tomcat-platform {tp['thr']:,.0f} req/s.")
    out.append(f"- **Tail latency (p99):** Netty-Loom {nl['p99']:,.0f} ms vs Tomcat+VT {tv['p99']:,.0f} "
               f"ms ({tv['p99'] / nl['p99']:.1f}×) vs Tomcat-platform {tp['p99']:,.0f} ms "
               f"({tp['p99'] / nl['p99']:.1f}×).")
    out.append(f"- **Error rate:** Netty-Loom {nl['err']:.2f}% vs Tomcat+VT {tv['err']:.2f}% "
               f"vs Tomcat-platform {tp['err']:.2f}%.")
    if nl["per_core"] and tv["per_core"]:
        out.append(f"- **CPU efficiency:** Netty-Loom {nl['cores']:.1f} cores → "
                   f"{nl['per_core']:,.0f} req/s per core vs Tomcat+VT {tv['cores']:.1f} cores → "
                   f"{tv['per_core']:,.0f} req/s per core.")
    out.append("")
    beats_vt = nl["p99"] < tv["p99"] and nl["err"] <= tv["err"] and nl["thr"] >= tv["thr"]
    if beats_vt:
        out.append("**Does flipping `spring.threads.virtual.enabled=true` on Tomcat close the gap?** "
                   "On this workload, **no** — Netty-Loom still wins on throughput, p99 tail, and error "
                   "rate. The wedge is more than a Tomcat config flag.")
    else:
        out.append("**Does flipping `spring.threads.virtual.enabled=true` on Tomcat close the gap?** "
                   "On this workload Tomcat+VT is competitive with Netty-Loom — read that honestly: "
                   "the cheaper recommendation may be to enable virtual threads on Tomcat.")
    out.append("")
    if nl["per_core"] and tv["per_core"]:
        if nl["per_core"] >= tv["per_core"]:
            out.append("**Is the throughput edge structural or a single-box contention artifact?** "
                       f"Structural: Netty-Loom does more work *per core* "
                       f"({nl['per_core']:,.0f} vs {tv['per_core']:,.0f} req/s/core), so the win isn't "
                       "merely from grabbing cores k6 left idle. Off-box, raw per-request efficiency may "
                       "compress, but a per-core advantage like this should persist.")
        else:
            out.append("**Is the throughput edge structural or a single-box contention artifact?** "
                       f"Partly contention: Netty-Loom's throughput/core ({nl['per_core']:,.0f}) is "
                       f"*below* Tomcat+VT's ({tv['per_core']:,.0f}), so some of its raw throughput comes "
                       "from pinning more cores on this contended box. The I/O-parallelism win should "
                       "persist off-box, but the raw-efficiency component would compress — confirm on "
                       "two hosts.")
        out.append("")
    out.append("> Single-box loopback numbers: the k6 client contends with the server for CPU, so "
               "absolute latencies are inflated and the comparison is relative, not absolute. The CPU "
               "efficiency table is the discriminator for what survives off-box. See "
               "[README](../README.md) caveats.")
    out.append("")

def scenario_table(scenario, heading, selector=""):
    out.append(f"## {heading}")
    out.append("")
    out.append(row(["Target", "Throughput (req/s)", "p50 (ms)", "p95 (ms)", "p99 (ms)", "Error rate"]))
    out.append(row(["---", "---:", "---:", "---:", "---:", "---:"]))
    for name, label in TARGETS:
        s = load_summary(name, scenario)
        dur = metric(s, "http_req_duration", selector)
        stats = scenario_stats(name, scenario, selector)
        if not stats["valid"]:
            # One "invalid" beats six plausible numbers: see scenario_stats.
            out.append(row([label] + ["invalid"] * 5))
            continue
        out.append(row([
            label,
            f(stats["thr"], 0),
            f(pick(dur, "p(50)", "med")),
            f(pick(dur, "p(95)")),
            f(stats["p99"]),
            f(stats["err"], 2, "%"),
        ]))
    out.append("")


scenario_table("low", "Scenario 1 — low-concurrency throughput (1→10 VUs, `GET /ping`)")
scenario_table("high", f"Scenario 2 — high-concurrency blocking I/O ({VUS:,} VUs, `GET /work`)")
scenario_table(SECURED_SCENARIO, f"Scenario 3 — high-concurrency behind Spring Security ({VUS:,} VUs, "
                          "`GET /work-secured`)", SECURED_SELECTOR)
out.append("Each VU authenticates once through `formLogin()` (with CSRF) and then rides its session "
           "cookie for the rest of the run, so the steady state measures an authenticated request "
           "through the whole filter chain — not the login. Login requests are tagged separately and "
           "excluded from the numbers above.")
out.append("")

# ---- Security overhead: scenario 2 vs scenario 3, same target, same jar, same run ----
nl_sec = scenario_stats("netty-loom", SECURED_SCENARIO, SECURED_SELECTOR)
tp_sec = scenario_stats("tomcat-platform", SECURED_SCENARIO, SECURED_SELECTOR)
tv_sec = scenario_stats("tomcat-virtual", SECURED_SCENARIO, SECURED_SELECTOR)

if any(v["thr"] is not None for v in (nl_sec, tp_sec, tv_sec)):
    out.append("## Security overhead (scenario 2 → scenario 3, same target and run)")
    out.append("")
    out.append(row(["Target", "`/work` (req/s)", "`/work-secured` (req/s)",
                    "Δ throughput (higher is better)", "`/work` p99 (ms)",
                    "`/work-secured` p99 (ms)", "Δ p99 (lower is better)"]))
    out.append(row(["---", "---:", "---:", "---:", "---:", "---:", "---:"]))
    for (_, label), plain, secured in zip(TARGETS, (nl, tp, tv), (nl_sec, tp_sec, tv_sec)):
        out.append(row([
            label,
            f(plain["thr"], 0), f(secured["thr"], 0), delta(plain["thr"], secured["thr"]),
            f(plain["p99"]), f(secured["p99"]), delta(plain["p99"], secured["p99"]),
        ]))
    out.append("")
    out.append("Δ is scenario 3 against scenario 2 on the *same* target, jar and run, so it isolates "
               "the Spring Security filter chain plus one session lookup per request. `/ping` and "
               "`/work` sit outside the chain (`securityMatcher` scopes it to the secured paths), so "
               "scenarios 1 and 2 stay broadly comparable to the pre-Security snapshot — they still "
               "pay `DelegatingFilterProxy` and the firewall on every request, which is small but "
               "not zero. Read the Δ with the run order in mind: scenario 3 always runs second, on a "
               f"JVM that has just served the scenario-2 plateau, and run-to-run noise on this "
               f"harness is around ±{NOISE_FLOOR_PCT:.0f}% — a Δ inside that band is not a "
               "measurement.")
    out.append("")

    def verdict(question, ours, theirs, won, lost):
        """One scripted paragraph per claim. A gap inside the noise floor is reported as neither."""
        if ours is None or theirs is None or not theirs:
            return
        if abs(ours - theirs) / theirs * 100 < NOISE_FLOOR_PCT:
            answer, body = "Too close to call", (
                f"{ours:,.0f} vs {theirs:,.0f} req/s is inside the ±{NOISE_FLOOR_PCT:.0f}% noise "
                "floor. Re-run on a quiet machine before claiming either way.")
        elif ours >= theirs:
            answer, body = "Yes", won
        else:
            answer, body = "No", lost
        out.append(f"**{question}** **{answer}** — {body}")
        out.append("")

    tomcat_secured = [v for v in (tv_sec["thr"], tp_sec["thr"]) if v is not None]
    best_tomcat_secured = max(tomcat_secured) if tomcat_secured else None
    verdict(
        "Do Security-protected endpoints still beat Tomcat?",
        nl_sec["thr"], best_tomcat_secured,
        won="like for like, both behind the same chain, Netty-Loom leads.",
        lost="like for like, Tomcat leads. Report it as it stands: the filter chain costs this "
             "stack more than it costs Tomcat.")
    verdict(
        "Does Netty-Loom behind Security still outrun Tomcat+VT on unsecured `/work`?",
        nl_sec["thr"], tv["thr"],
        won="the stronger claim holds on this run — the chain's cost does not erase the gap.",
        lost="the chain's cost is not free on this workload.")

# ---- CPU efficiency (the structural-win vs contention-artifact discriminator) ----
out.append(f"## CPU efficiency (server-side, during the {VUS:,}-connection run)")
out.append("")
out.append(row(["Target", "Throughput (req/s)", "CPU (avg cores)", f"CPU util % (of {NCORES})",
                "Throughput / core (req/s)"]))
out.append(row(["---", "---:", "---:", "---:", "---:"]))
for name, label in TARGETS:
    s = load_summary(name, "high")
    thr = pick(metric(s, "http_reqs"), "rate")
    cores = cpu_avg_cores(f"{name}_high_load.csv")
    util = (cores / NCORES * 100) if cores is not None else None
    per_core = (thr / cores) if (thr is not None and cores) else None
    out.append(row([
        label,
        f(thr, 0),
        f(cores, 2),
        f(util, 1, "%"),
        f(per_core, 0),
    ]))
out.append("")
out.append("Server-process CPU only (the k6 client is a separate process). **CPU (avg cores)** = "
           "Δ(cumulative CPU time) / Δ(wall clock) over the load window, so it counts only CPU burned "
           "while serving the load, not startup/JIT. **Throughput / core** is the discriminator: at "
           f"{VUS:,} connections the client and server contend for the same {NCORES} cores, so raw "
           "throughput partly reflects who grabbed more cores. Throughput-per-core is allocation-"
           "independent — a higher value means more requests served per core of work, an efficiency "
           "edge that should survive moving the client off-box. A target can post high throughput "
           "simply by pinning more cores; only a higher throughput-per-core says the win is structural.")
out.append("")

# ---- Memory per connection ----
out.append(f"## Memory per connection (under {VUS:,} concurrent connections)")
out.append("")
out.append(row(["Target", "Idle RSS (MB)", "Loaded RSS median (MB)", "Loaded RSS peak (MB)",
                "Δ RSS median (MB)", "Memory / connection (KB)"]))
out.append(row(["---", "---:", "---:", "---:", "---:", "---:"]))
for name, label in TARGETS:
    idle = rss_kb(f"{name}_idle.csv")
    load = rss_kb(f"{name}_high_load.csv")
    idle_med = statistics.median(idle) if idle else None
    load_med = statistics.median(load) if load else None
    load_peak = max(load) if load else None
    rss_delta = (load_med - idle_med) if (idle_med is not None and load_med is not None) else None
    per_conn = (rss_delta / VUS) if rss_delta is not None else None
    out.append(row([
        label,
        f(mb(idle_med)),
        f(mb(load_med)),
        f(mb(load_peak)),
        f(mb(rss_delta)),
        f(per_conn, 2),
    ]))
out.append("")
out.append("RSS includes committed heap, thread stacks, and Netty direct buffers. "
           "`Memory / connection = (loaded RSS median − idle RSS median) / connections`, using the "
           "steady-state median rather than the transient peak to suppress G1 heap-commit/JIT jitter "
           "(a ~100MB noise floor with `-Xmx2g` and no `-Xms`). At low connection counts this metric is "
           "noise-dominated and not meaningful; it only separates the targets once connections vastly "
           "outnumber the platform-thread pool (~200). Near-zero or negative values mean per-connection "
           "growth was below the noise floor — expected for virtual-thread targets whose parked "
           "continuations are cheap. Note the platform-thread target's footprint does **not** scale with "
           "offered connections: it caps at ~200 worker threads and refuses/queues the rest, so it can "
           "look memory-frugal while collapsing on latency and error rate above. Measured on scenario "
           "2 only: the secured run holds one authenticated `HttpSession` per VU for the whole "
           "plateau, so dividing its RSS growth by connections would quietly be measuring sessions.")
out.append("")

print("\n".join(out))
