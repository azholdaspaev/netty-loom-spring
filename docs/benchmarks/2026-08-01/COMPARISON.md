# Netty-Loom vs Tomcat — full benchmark sweep, 2026-08-01/02

Three sweeps against the NL-27 harness at commit `ef72e89` (PR #107), covering all three k6 scenarios
against all three targets.

| Sweep | VUs | Target order | Purpose |
| --- | ---: | --- | --- |
| `pass1-forward/` | 10,000 | netty → tomcat-platform → tomcat-virtual | Primary run, the harness's documented order |
| `pass2-reversed/` | 10,000 | tomcat-virtual → tomcat-platform → netty | Crossover — does the ranking survive flipping the order? |
| `pass3-2k-forward/` | 2,000 | netty → tomcat-platform → tomcat-virtual | The only load level where the **secured** scenario completes |

Each sweep is a full `run-all.sh` invocation: identical JVM flags, per-target start/health/idle-sample,
scenarios 1–3, memory sampling under scenario 2, teardown, settle. Nine k6 runs per sweep, 27 in total.

---

## 1. Method and caveats — read before the numbers

- **Single 8-core box.** The k6 load generator contends with the server for the same 8 logical cores.
  Absolute latencies are inflated; the comparison is relative. Throughput-per-core is the metric that
  should survive moving the client off-box.
- **Machine state.** `Darwin 25.5.0 arm64`, 16 GB, JDK 25.0.2, k6 v1.4.2. Spotify was closed before pass 1;
  WindowServer (~30–45%) and the editor could not be. Per-pass snapshots are in each `machine-load.txt`.
- **Noise floor ±11%**, as encoded in `summarize.py`. **No difference inside that band is reported here as
  a result**, regardless of what the generated SNAPSHOT prose says.
- **Coordinated omission is not corrected.** k6 measures from request dispatch, so a server that stalls
  its queue is partly flattered.
- **`SETTLE=35`** between every scenario and target, to drain macOS TIME_WAIT (16,384 ephemeral ports,
  ~30s recycle) — the binding constraint at these connection counts, not file descriptors.
- **The 10,000-VU secured scenario is not measurable on this box.** See §4.

---

## 2. Headline

At **10,000 concurrent blocking connections** on `GET /work` (a `Thread.sleep(50)` standing in for a
blocking DB call), averaged across both passes:

| Metric | Netty-Loom | Tomcat + virtual threads | Tomcat, platform threads |
| --- | ---: | ---: | ---: |
| Throughput (req/s) | **45,857** | 25,576 | 3,727 |
| p99 latency (ms) | **384** | 2,666 (6.9×) | 2,794 (7.3×) |
| Throughput per core (req/s) | **18,650** | 7,957 | 8,515 |
| Memory per connection (KB) | 49.2 | 199.8 (4.1×) | 44.6 |
| Error rate | 0.00% | 0.00% | 0.00% |

**But the advantage is concurrency-dependent, and that is the most important result of this sweep.**
At **2,000** connections, Netty-Loom and Tomcat+VT are statistically indistinguishable:

| Load level | Netty-Loom (req/s) | Tomcat+VT (req/s) | Gap | p99: Netty vs Tomcat+VT |
| --- | ---: | ---: | ---: | --- |
| 2,000 VUs | 30,885 | 30,128 | **+2.5% — inside noise** | 93.0 ms vs 93.2 ms — identical |
| 10,000 VUs | 45,857 | 25,576 | **+79% — real** | 384 ms vs 2,666 ms — 6.9× |

Tomcat+VT is a perfectly good server at 2,000 connections. The wedge opens as connections climb.
Any claim about this library's advantage should be stated **with its concurrency level attached**.

---

## 3. Per-scenario detail

### Scenario 1 — `GET /ping`, 1→10 VUs

| Target | pass1 | pass2 | pass3 | Verdict |
| --- | ---: | ---: | ---: | --- |
| Netty-Loom | 27,263 | 27,710 | 26,897 | consistently lowest |
| Tomcat, platform | 28,530 | 29,622 | 29,365 | |
| Tomcat, virtual | 28,988 | 28,274 | 29,150 | |

p50/p95/p99 identical across all targets and runs (0.1 / 0.2 / 0.3 ms), 0.00% errors everywhere.

Netty-Loom is **4–8% slower on trivial requests** in all three runs. Each individual gap sits inside the
±11% floor, but the sign is the same in three independent runs across two orders, so it reads as a small
real per-request overhead rather than noise. It is irrelevant at this scale (a 0.1 ms difference) but worth
knowing: this library's edge is not per-request speed, it is behaviour under concurrency.

### Scenario 2 — `GET /work`, blocking I/O

**At 10,000 VUs** (pass1 → pass2):

| Target | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | Errors |
| --- | ---: | ---: | ---: | ---: | ---: |
| Netty-Loom | 45,332 → 46,382 | 136 → 141 | 328 → 333 | 388 → 382 | 0.00% |
| Tomcat, platform | 3,714 → 3,739 | 2,684 → 2,680 | 2,805 → 2,737 | 2,835 → 2,753 | 0.00% |
| Tomcat, virtual | 25,068 → 26,084 | 78 → 77 | 1,724 → 1,604 | 2,756 → 2,575 | 0.00% |

**At 2,000 VUs:**

| Target | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | Errors |
| --- | ---: | ---: | ---: | ---: | ---: |
| Netty-Loom | 30,885 | 52.6 | 69.8 | 93.0 | 0.00% |
| Tomcat, platform | 3,610 | 544.0 | 566.6 | 576.0 | 0.00% |
| Tomcat, virtual | 30,128 | 53.5 | 75.4 | 93.2 | 0.00% |

Note Tomcat+VT's **p50 is better than Netty-Loom's** at 10k (77 ms vs 141 ms) while its p99 is 7× worse.
Its median request is fast and its tail collapses — the classic signature of a server that serves a
favoured subset well and queues the rest. Netty-Loom's p50→p99 spread is 141→382 ms; Tomcat+VT's is
77→2,575 ms. For latency SLOs, the tail is what matters.

Tomcat-platform is flat at ~3,700 req/s regardless of whether 2,000 or 10,000 connections are offered —
its 200-thread pool is the ceiling, and everything above it queues. It reports 0.00% errors because it
*queues* rather than refuses, which is why its p99 (~2.8 s) is the honest signal, not its error rate.

### Scenario 3 — `GET /work-secured`, 2,000 VUs

Only the 2,000-VU sweep produced valid data (§4).

| Target | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | Errors |
| --- | ---: | ---: | ---: | ---: | ---: |
| Netty-Loom | 23,296 | 53.1 | 78.7 | 101.7 | 0.00% |
| Tomcat, platform | 2,602 | 553.2 | 577.1 | 3,542.0 | 0.00% |
| Tomcat, virtual | 22,208 | 54.7 | 86.3 | 108.9 | 0.00% |

Every VU authenticated once through `formLogin()` with CSRF, then rode its session cookie. Netty-Loom
served **1,864,808** authenticated requests with 100% of checks passing and zero aborts.

---

## 4. The 10,000-VU secured scenario could not be measured — and the harness published it anyway

**All six** secured runs at 10,000 VUs (3 targets × 2 passes) aborted with
`test aborted: no CSRF token on .../login (status 0)`. The cause is client-side and identical everywhere:
scenario 3's ramp asks all 10,000 VUs to perform `GET /login` + `POST /login` within 15 s — 20,000
session-creating requests — where scenario 2 opens one connection per VU and reuses it. A handful exceed
k6's 30 s request timeout, and `high-concurrency-secured.js` calls `exec.test.abort()` on *any* login
failure, killing all 10,000 VUs.

This is a harness scale limit, not a server defect: it reproduced on every target in both orders.

**The serious part is what `summarize.py` did with the wreckage.** Its validity gate is:

```python
valid = scenario != SECURED_SCENARIO or (
    not metric(s, "checks").get("fails") and pick(reqs, "count") != 0)
```

It tests for failed checks and a zero request count — but **not for whether the test aborted**. A run that
dies after completing a few requests passes both conditions, because checks are only evaluated on requests
that were actually issued, so a truncated run has a *perfect* check rate by construction.

Result — three of the six aborted runs were published as if they were measurements:

| Run | Aborted | Work reqs completed | Gate verdict | Published |
| --- | --- | ---: | --- | --- |
| pass1 netty-loom | yes | 0 | `invalid` ✅ | — |
| pass1 tomcat-platform | yes | 0 | `invalid` ✅ | — |
| pass1 tomcat-virtual | yes | 1,660 | **valid** ❌ | 55 req/s, p99 18,468 ms |
| pass2 netty-loom | yes | 96 | **valid** ❌ | 3 req/s, p99 20,300 ms |
| pass2 tomcat-platform | yes | 657 | **valid** ❌ | 18 req/s, p99 28,940 ms |
| pass2 tomcat-virtual | yes | 0 | `invalid` ✅ | — |

And because those leaked numbers reached the verdict logic, **pass 2's SNAPSHOT.md states a confident
competitive conclusion built entirely on aborted runs**:

> **Do Security-protected endpoints still beat Tomcat?** **No** — like for like, Tomcat leads. Report it as
> it stands: the filter chain costs this stack more than it costs Tomcat.

That sentence is false, and §5 shows the properly-measured answer is the opposite of it. Treat the
Scenario 3 and Security-overhead sections of `pass1-forward/SNAPSHOT.md` and `pass2-reversed/SNAPSHOT.md`
as **void**. Only `pass3-2k-forward/SNAPSHOT.md` carries real secured data.

---

## 5. Security overhead — issue #27's actual question

From the 2,000-VU sweep, comparing `/work` against `/work-secured` on the same target, jar and run:

| Target | `/work` req/s | `/work-secured` req/s | Δ throughput | `/work` p99 | `/work-secured` p99 | Δ p99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Netty-Loom | 30,885 | 23,296 | **−24.6%** | 93.0 ms | 101.7 ms | **+9.3%** |
| Tomcat, platform | 3,610 | 2,602 | −27.9% | 576.0 ms | 3,542.0 ms | +514.9% |
| Tomcat, virtual | 30,128 | 22,208 | **−26.3%** | 93.2 ms | 108.9 ms | **+16.9%** |

**The Spring Security filter chain costs all three servers roughly the same: about a quarter of throughput.**
The spread across targets (−24.6% / −26.3% / −27.9%) is well inside the ±11% floor, so the correct reading
is *uniform cost*, not "Netty-Loom handles Security better." What matters is the negative result: **there is
no penalty specific to this library's servlet bridge.** Routing a request through `DelegatingFilterProxy`,
the firewall, the filter chain and a session lookup costs the Netty bridge no more than it costs Tomcat.
That is the answer issue #27 was asking for.

Two secondary observations:

- **Netty-Loom vs Tomcat+VT on secured traffic: too close to call.** 23,296 vs 22,208 req/s is +4.9%, inside
  the noise floor. At 2,000 VUs they are equivalent, exactly as they are on unsecured `/work`.
- **Tomcat-platform's secured tail collapses**: p99 goes 576 ms → 3,542 ms, a +515% blow-up, while its
  throughput falls only in line with the others. Session contention on top of an already-saturated
  200-thread pool. Its p95 (577 ms) is unchanged — the damage is confined to the last few percent of
  requests, which is precisely where a bounded pool with contended shared state hurts.

---

## 6. Resource efficiency

### CPU (server process only, during the load plateau)

| Target | pass1 (10k) | pass2 (10k) | pass3 (2k) |
| --- | --- | --- | --- |
| Netty-Loom | 2.47 cores → **18,345** req/s/core | 2.45 → **18,955** | 1.70 → **18,202** |
| Tomcat, platform | 0.43 → 8,598 | 0.44 → 8,431 | 0.48 → 7,554 |
| Tomcat, virtual | 3.21 → 7,812 | 3.22 → 8,101 | 1.99 → **15,177** |

This is the most structurally revealing table in the sweep. **Netty-Loom's throughput-per-core is flat at
~18,200–19,000 across both concurrency levels and both run orders** — five independent measurements within
4% of each other. Its efficiency does not degrade as concurrency rises.

**Tomcat+VT's efficiency halves**: 15,177 req/s/core at 2,000 connections → ~7,950 at 10,000. It burns more
CPU (3.2 cores vs Netty's 2.5) to deliver 45% less throughput. That degradation curve, not the headline
throughput number, is the real architectural difference — and because throughput-per-core is independent of
how the two processes split the box, it is the finding most likely to survive moving the client off-box.

### Memory per connection

Meaningful only at 10,000 connections; at 2,000 the metric is noise-dominated (fixed heap growth divided by
fewer connections inflates every target — Netty reads 163 KB at 2k vs 49 KB at 10k, which is an artifact,
not a regression).

| Target | pass1 (10k) | pass2 (10k) | Loaded RSS median (pass2) |
| --- | ---: | ---: | ---: |
| Netty-Loom | 53.23 KB | 45.12 KB | 677 MB |
| Tomcat, platform | 45.27 KB | 43.99 KB | 665 MB |
| Tomcat, virtual | 199.96 KB | 199.62 KB | 2,193 MB |

Tomcat+VT holds **~4.4× more memory per connection** and a 2.2 GB resident set against Netty-Loom's 677 MB
for the same 10,000 connections — the most stable and largest-margin difference in the entire sweep
(199.96 vs 199.62 across reversed orders is remarkable reproducibility). Tomcat-platform's low figure is not
frugality: it caps at ~200 worker threads and queues the rest, so its footprint does not scale with offered
load in the first place.

---

## 7. Crossover agreement — does the ranking survive reversing the order?

The reason for running pass 2 backwards. Scenario 2, 10,000 VUs:

| Metric | pass1 (forward) | pass2 (reversed) | Δ | Inside ±11%? |
| --- | ---: | ---: | ---: | --- |
| Netty-Loom throughput | 45,332 | 46,382 | +2.3% | ✅ |
| Tomcat-platform throughput | 3,714 | 3,739 | +0.7% | ✅ |
| Tomcat-virtual throughput | 25,068 | 26,084 | +4.1% | ✅ |
| Netty-Loom p99 | 387.6 ms | 381.5 ms | −1.6% | ✅ |
| Tomcat-virtual p99 | 2,756 ms | 2,575 ms | −6.6% | ✅ |
| Netty-Loom req/s/core | 18,345 | 18,955 | +3.3% | ✅ |
| Tomcat-virtual req/s/core | 7,812 | 8,101 | +3.7% | ✅ |
| Tomcat-virtual KB/conn | 199.96 | 199.62 | −0.2% | ✅ |

**Every metric agrees within 7%, and the ranking is identical in both directions.** Notably, pass 2 ran
`netty-loom` *last* — the position most penalised by thermal drift and accumulated TIME_WAIT — and it still
posted its best throughput of the sweep. The scenario 1 and 2 conclusions are order-independent and can be
reported as findings.

Scenario 3 at 10k agrees on nothing, because all six runs aborted (§4). Its disagreement is not a
measurement conflict, it is the absence of a measurement.

---

## 8. Against the committed 2026-06-13 baseline

`netty-loom-spring-benchmarks/results/SNAPSHOT.md`, same machine and VU count, before the Security work:

| Metric | Baseline (2026-06-13) | This sweep (avg of 2 passes) | Change |
| --- | ---: | ---: | --- |
| Netty-Loom throughput | 41,412 | 45,857 | +10.7% |
| **Tomcat+VT throughput** | **12,640** | **25,576** | **+102%** |
| Tomcat-platform throughput | 3,834 | 3,727 | −2.8% |
| Netty-Loom p99 | 447 ms | 384 ms | −14% |
| Tomcat+VT p99 | 5,417 ms | 2,666 ms | −51% |
| Netty-Loom KB/conn | 20.17 | 49.2 | **+144%** |
| Tomcat+VT KB/conn | 148.50 | 199.8 | +35% |

Two things demand attention:

**(a) The published Tomcat+VT number is too flattering to this library.** The baseline's 12,640 req/s is
half what Tomcat+VT delivers today, measured twice in opposite orders. The baseline single-run figure looks
like an under-measurement. The honest advantage over Tomcat+VT at 10k VUs is **~1.8×, not the ~3.3× the
committed snapshot implies.** The README and root README quote the baseline numbers and should be corrected.

**(b) Netty-Loom's memory per connection more than doubled** (20.17 → 45–53 KB). All three targets rose, so
some of this is environmental, but Netty-Loom rose most in relative terms. The obvious suspect is that
`spring-boot-starter-security` is now on the classpath: Boot registers `DelegatingFilterProxy` at `/*`
regardless of the narrow `securityMatcher`, so even `/work` now pays proxy dispatch and `StrictHttpFirewall`
per request. Δ RSS moved 197 MB → 520 MB, far outside the ~100 MB heap-commit noise floor this harness
documents. **This is not established as a regression — it is an unexplained delta that needs one A/B run
with and without the Security starter to resolve.** It should not be published as a memory figure until
then.

---

## 9. Defects found, none fixed here

1. **`summarize.py` publishes aborted runs** (§4). The validity gate misses `test aborted`, and the leaked
   numbers reach the verdict text, producing a confident and false competitive claim. A fix would assert
   the k6 run did not abort — e.g. check `iterations` against expected, or have `run-all.sh` record k6's
   exit code (it currently discards it) and have `summarize.py` refuse any scenario whose run failed.
2. **`high-concurrency-secured.js` is all-or-nothing at scale.** One login timeout among 10,000 VUs aborts
   the whole test via `exec.test.abort()`. A tolerance (abort only past a failure *rate*) or a gentler login
   ramp would make the 10k secured scenario measurable.
3. **Netty-Loom floods its log on shutdown.** On SIGTERM with requests in flight it wrote **81 MB (pass1)
   and 100 MB (pass2)** of stack traces — 8,148 paired `RejectedExecutionException: event executor
   terminated` and `IllegalStateException: The servlet context has been closed`. Both Tomcat targets wrote
   **2.9 KB** for the same event. The exact pairing suggests the servlet context closes while requests are
   still being dispatched, rather than after a drain — worth checking against the Boot 4 graceful-shutdown
   path. This is an operational defect regardless of the benchmark.

None were touched; this sweep was measurement only.

---

## 10. What can be claimed

Supported by this sweep:

- At **10,000** concurrent blocking connections, Netty-Loom delivers **~1.8× the throughput and ~7× better
  p99** than Tomcat with virtual threads, on **~2.3× better throughput-per-core** and **~4.4× less memory
  per connection**. Confirmed in both run orders.
- Netty-Loom's **throughput-per-core is constant** (~18,200–19,000) from 2,000 to 10,000 connections, while
  Tomcat+VT's **halves**. This is the structural claim.
- The **Spring Security filter chain costs this library no more than it costs Tomcat** (−24.6% vs −26.3%
  throughput at 2,000 VUs, a difference inside noise) — issue #27's question, answered.

Not supported, and should not be claimed:

- Any advantage at **2,000** connections or below — Netty-Loom and Tomcat+VT are equivalent there.
- Any per-request speed advantage — Netty-Loom is consistently **4–8% slower** on trivial `/ping`.
- Any secured-workload comparison at **10,000** VUs — not measurable on this hardware.
- The **~3.3× advantage** implied by the committed baseline — today's data says ~1.8×.
- The current **memory-per-connection** figure, until the Security-starter A/B is run.

## Reproducing

```bash
./gradlew :netty-loom-spring-example-netty:bootJar :netty-loom-spring-example-tomcat:bootJar
cd netty-loom-spring-benchmarks
VUS=10000 DURATION=60s RAMP=15s SETTLE=35 \
  RESULTS_DIR=../docs/benchmarks/<date>/pass1-forward bash scripts/run-all.sh
# pass 2: same, with the three benchmark_target calls in reversed order
# pass 3: same, VUS=2000 — required for the secured scenario to complete
```

`*.k6.log` and `*.server.log` in these directories are **not tracked** — the root `.gitignore` carries a
blanket `*.log`. The `.json`, `.csv` and `.md` artifacts are. Adding `!docs/benchmarks/**/*.log` would
preserve the raw k6 output; note the Netty server logs are ~100 MB each until defect 3 is fixed.
