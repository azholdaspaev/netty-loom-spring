# Netty-Loom vs Tomcat — 10,000-VU sweep, 2026-08-09

One sweep against the NL-27 harness at commit `734ef88`, covering all three k6 scenarios against all
three targets. Compared against `docs/benchmarks/2026-08-01/COMPARISON.md` (commit `ef72e89`), 73
commits earlier.

| Sweep | VUs | Target order | Purpose |
| --- | ---: | --- | --- |
| `pass1-forward/` | 10,000 | netty → tomcat-platform → tomcat-virtual | The harness's documented order |

Nine k6 runs. **All nine exited 0 and every row published** — the first sweep in this series with no
refused measurement, and in particular the first with valid secured data at 10,000 VUs.

---

## 1. Method and caveats — read before the numbers

- **Single 8-core box.** k6 contends with the server for the same 8 logical cores. Absolute latencies
  are inflated; the comparison is relative. Throughput-per-core is the metric expected to survive
  moving the client off-box.
- **Machine state.** `Darwin 25.5.0 arm64`, Apple M1 Pro, 16 GB, JDK 25.0.2 (Temurin), k6 v1.4.2 —
  the same box and toolchain as the 2026-08-01 sweep, so the two are directly comparable. Pre-run
  load average 1.97; WindowServer (~42%) and the editor could not be closed. Full snapshot in
  `pass1-forward/machine-load.txt`.
- **Noise floor ±11%**, as encoded in `summarize.py`. **No difference inside that band is reported
  here as a result.** This box was no quieter than the old sweep's, so ±11% is a floor, not a ceiling.
- **This is one pass, not three.** The 2026-08-01 sweep ran a reversed pass and a 2,000-VU pass; this
  one did not. Everything that rested on those is listed in §9 as *not re-tested*, and nothing here
  restates a 2026-08-01 conclusion as though this sweep had reconfirmed it.
- **Cross-sweep deltas are weaker evidence than within-run gaps.** All three targets in a single pass
  run minutes apart on one machine, so a netty-vs-tomcat ratio controls for machine state in a way a
  netty-today-vs-netty-last-week delta cannot. Where the two disagree, §3 says so.
- **Coordinated omission is not corrected.** k6 measures from request dispatch, so a server that
  stalls its queue is partly flattered.
- **`SETTLE=35`** between every scenario and target, to drain macOS TIME_WAIT (16,384 ephemeral
  ports, ~30 s recycle) — the binding constraint at these connection counts.

---

## 2. Headline

At **10,000 concurrent blocking connections** on `GET /work` (a `Thread.sleep(50)` standing in for a
blocking DB call):

| Metric | Netty-Loom | Tomcat + virtual threads | Tomcat, platform threads |
| --- | ---: | ---: | ---: |
| Throughput (req/s) | **44,584** | 26,819 | 3,663 |
| p99 latency (ms) | **420** | 2,409 (5.7×) | 2,831 (6.7×) |
| Throughput per core (req/s) | **20,336** | 8,322 | 8,069 |
| CPU (avg cores) | 2.19 | 3.22 | 0.45 |
| Memory per connection (KB) | 66.6 | 206.8 (3.1×) | 44.1 |
| Error rate | 0.00% | 0.00% | 0.00% |

**Nothing on the hot path regressed.** Against the 2026-08-01 two-pass mean, throughput moved −2.8%,
p99 +9.5% and throughput-per-core +9.0% — every one inside ±11%. The 73 commits in this range,
including NL-37's rewrite of the response write path, cost no measurable throughput or tail latency.

Two findings do stand out, and they pull in opposite directions:

- **Memory per connection rose 35%** (49.2 → 66.6 KB), the only cross-sweep metric outside the noise
  band. §6.
- **The secured scenario is measurable for the first time**, and Netty-Loom wins it. §4, §5.

---

## 3. Per-scenario detail

### Scenario 1 — `GET /ping`, 1→10 VUs

| Target | Throughput (req/s) | avg (ms) | p50 (ms) | p95 (ms) | Errors |
| --- | ---: | ---: | ---: | ---: | ---: |
| Netty-Loom | 24,540 | 0.1493 | 0.133 | 0.263 | 0.00% |
| Tomcat, platform | 29,715 | 0.1212 | 0.109 | 0.208 | 0.00% |
| Tomcat, virtual | 28,791 | 0.1256 | 0.113 | 0.216 | 0.00% |

**This is the one place the new code looks worse, and it needs stating carefully.** The old sweep
found Netty-Loom 4–8% slower on trivial requests. Here it is **+23.2% slower than tomcat-platform
and +18.9% slower than tomcat-virtual on mean per-request latency** — roughly triple the old gap.

The two ways of reading it disagree, and the disagreement is the point:

- *Cross-sweep*, Netty-Loom's own scenario-1 throughput fell 27,487 → 24,540, **−10.7%** — just
  inside ±11%, so on its own it is not reportable.
- *Within-run*, both Tomcat targets landed within 2.2% of their old figures on the same box in the
  same pass, while Netty-Loom alone moved. A within-run comparison controls for machine state, and
  by that control the gap widened from ~4–8% to ~19–23%.

The absolute cost is **+28 microseconds per request**, irrelevant at `/work` scale and invisible in
scenario 2. But the shape is exactly that of added *fixed per-dispatch* work, and this range added
four candidates: NL-138's `HttpDecoderFailureHandler` (a new pipeline step per inbound message),
NL-37's per-dispatch `HttpChannelResponseWriter` allocation and `ResponseState` machine, NL-17's
`fireRequestInitialized`/`fireRequestDestroyed` bracketing, and NL-108's two atomics per request.

**Treated as a signal to confirm, not a result.** One pass cannot separate it from run-order effects,
and the cross-sweep delta stays inside the noise floor. A reversed pass would settle it cheaply.

### Scenario 2 — `GET /work`, blocking I/O, 10,000 VUs

| Target | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | Errors |
| --- | ---: | ---: | ---: | ---: | ---: |
| Netty-Loom | 44,584 | 132.5 | 345.4 | 420.3 | 0.00% |
| Tomcat, platform | 3,663 | 2,758.6 | 2,812.4 | 2,830.5 | 0.00% |
| Tomcat, virtual | 26,819 | 74.8 | 1,562.9 | 2,408.9 | 0.00% |

The 2026-08-01 signature reproduces exactly. Tomcat+VT's **p50 is better than Netty-Loom's**
(74.8 ms vs 132.5 ms) while its p99 is 5.7× worse — a fast median over a collapsing tail, the
signature of a server that serves a favoured subset well and queues the rest. Netty-Loom's p50→p99
spread is 133→420 ms; Tomcat+VT's is 75→2,409 ms. For latency SLOs, the tail is what matters.

Tomcat-platform sits at ~3,663 req/s with a ~2.8 s p99 and reports 0.00% errors because it *queues*
rather than refuses. Its 200-thread pool is the ceiling; the error rate is not the honest signal, the
p99 is.

### Scenario 3 — `GET /work-secured`, 10,000 VUs

| Target | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | Errors |
| --- | ---: | ---: | ---: | ---: | ---: |
| Netty-Loom | **38,726** | 185.6 | 413.2 | 485.9 | 0.00% |
| Tomcat, platform | 3,655 | 2,755.9 | 2,831.8 | 2,852.8 | 0.00% |
| Tomcat, virtual | 22,446 | 82.5 | 2,191.2 | 3,407.6 | 0.00% |

All three targets authenticated **all 10,000 VUs** — 20,000 login requests each (`GET /login` +
`POST /login`), `login_failed` rate 0 across 10,000 samples, zero failed checks. Netty-Loom served
**3,099,995** authenticated requests, Tomcat+VT 1,796,456, Tomcat-platform 293,820.

Netty-Loom leads Tomcat+VT by **1.73× on throughput and 7.0× on p99**. The tail gap is *wider* under
Security than without it (7.0× vs 5.7×).

Login cost, tagged separately and excluded from the table above:

| Target | login p50 | login p95 | login p99 | login max |
| --- | ---: | ---: | ---: | ---: |
| Netty-Loom | 28.0 ms | 191.9 ms | 325.0 ms | 761 ms |
| Tomcat, platform | 1,054.2 ms | 2,125.7 ms | 2,360.6 ms | 2,411 ms |
| Tomcat, virtual | 10.4 ms | 386.6 ms | 2,032.1 ms | 4,038 ms |

Tomcat+VT has the fastest median login and the slowest maximum — the same tail behaviour as its
steady state, and the reason its logins used to time out (§4).

---

## 4. The 10,000-VU secured scenario now measures — and the old diagnosis was wrong

The 2026-08-01 sweep could not measure this scenario: all six runs aborted with
`no CSRF token on .../login (status 0)`. §4 of that document attributed it to a **k6 harness scale
limit** — 20,000 session-creating requests inside a 15 s ramp, with `exec.test.abort()` on any single
login failure.

That diagnosis was wrong, or at best incomplete. The actual cause was server-side: Spring Security's
`DelegatingPasswordEncoder` reports the `{noop}bench` credential as out of date, so
`InMemoryUserDetailsManager` **re-encodes the user with bcrypt on the first successful login**. That
put one key derivation per virtual user inside the measurement window, saturating the server and
timing out the logins that k6 then read as transport failures.

Both causes were fixed in this range, and the fix is visible in the result:

- **NL-111** added a `NoOpPasswordEncoder` bean to `BenchmarkSecurityConfig` in both example apps,
  removing the bcrypt work — the actual fix.
- **NL-111** also made `high-concurrency-secured.js` tolerant: a status-0 (transport) failure now
  returns a reason and the VU retries with backoff; only a server-*answered* rejection aborts.
- **NL-110** made `summarize.py` gate on k6's recorded exit code, so an aborted run is refused rather
  than published.

The lesson worth carrying: the old sweep reproduced the failure "on every target in both orders" and
concluded it was therefore a client-side limit. It reproduced everywhere because *every target ran
the same contaminated Spring Security configuration* — a shared server-side cause, not a client one.

**Consequence for the old document:** its §5 security-overhead table (2,000 VUs: −24.6% / −26.3% /
−27.9%) was measured with bcrypt still firing. It is not a valid baseline, and §5 below is therefore
presented as a first clean measurement rather than as a delta against it.

---

## 5. Security overhead at 10,000 VUs — first clean measurement

Comparing `/work` against `/work-secured` on the same target, jar and run:

| Target | `/work` req/s | `/work-secured` req/s | Δ throughput | `/work` p99 | `/work-secured` p99 | Δ p99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Netty-Loom | 44,584 | 38,726 | **−13.1%** | 420.3 ms | 485.9 ms | **+15.6%** |
| Tomcat, platform | 3,663 | 3,655 | −0.2% | 2,830.5 ms | 2,852.8 ms | +0.8% |
| Tomcat, virtual | 26,819 | 22,446 | **−16.3%** | 2,408.9 ms | 3,407.6 ms | **+41.5%** |

**The Spring Security filter chain costs Netty-Loom no more than it costs Tomcat+VT** — −13.1% vs
−16.3%, a 3.2-point spread well inside ±11%. That is the negative result issue #27 was asking for:
routing a request through `DelegatingFilterProxy`, `StrictHttpFirewall`, the filter chain and a
session lookup carries **no penalty specific to this library's servlet bridge**.

On the tail the two diverge, and here Netty-Loom is genuinely better: +15.6% p99 against Tomcat+VT's
+41.5%. Adding Security widens the p99 gap between them from 5.7× to 7.0×.

**Tomcat-platform's −0.2% is not efficiency.** It was already saturated at ~3,660 req/s by its
200-thread pool in scenario 2, so the filter chain costs it nothing it was not already losing to
queueing. Read its row as "already at the ceiling", not "cheapest Security".

Note this is a *different* measurement from the old §5, not a corrected one: 10,000 VUs here versus
2,000 there, and without the bcrypt contamination. The two numbers are not comparable, and the
smaller overhead seen here should not be reported as an improvement.

---

## 6. Resource efficiency

### CPU (server process only, during the load plateau)

| Target | Cores | Util % of 8 | req/s/core | 2026-08-01 req/s/core | Δ |
| --- | ---: | ---: | ---: | ---: | ---: |
| Netty-Loom | 2.19 | 27.4% | **20,336** | 18,650 | +9.0% |
| Tomcat, platform | 0.45 | 5.7% | 8,069 | 8,515 | −5.2% |
| Tomcat, virtual | 3.22 | 40.3% | 8,322 | 7,957 | +4.6% |

Netty-Loom delivers **2.44× Tomcat+VT's throughput per core** while burning **less** CPU in absolute
terms (2.19 cores vs 3.22). Every delta against the old sweep is inside the noise band, so the honest
statement is that per-core efficiency is unchanged — which, given 73 commits landed on this path, is
itself the finding.

Because throughput-per-core is independent of how the two processes split the box, this remains the
result most likely to survive moving the client off-box.

### Memory per connection

| Target | Idle RSS | Loaded RSS median | Δ RSS | KB/connection | 2026-08-01 | Δ |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Netty-Loom | 236.8 MB | 886.9 MB | 650.1 MB | **66.57** | 49.2 | **+35.3%** |
| Tomcat, platform | 223.7 MB | 654.2 MB | 430.4 MB | 44.08 | 44.6 | −1.2% |
| Tomcat, virtual | 215.8 MB | 2,235.1 MB | 2,019.3 MB | 206.78 | 199.8 | +3.5% |

**This is the only cross-sweep metric outside the ±11% band, and it is a regression in the direction
that matters.** Both Tomcat targets reproduced their old figures within 3.5%, which is strong evidence
the move is real and not environmental — the same box, the same hour, the same harness.

The trend across three sweeps is monotonic and steep:

| Sweep | Netty-Loom KB/conn |
| --- | ---: |
| 2026-06-13 (committed baseline) | 20.17 |
| 2026-08-01 | 49.2 |
| 2026-08-09 (this sweep) | **66.57** |

That is **3.3× the June figure**. The old document flagged the first jump as "an unexplained delta
that needs one A/B run with and without the Security starter to resolve" and that A/B was never run,
so the two increments are still unattributed. NL-37 adds new candidates on top: `getBufferSize()`
now returns a real `DEFAULT_BUFFER_SIZE` of 8,192 where it previously returned 0, and each in-flight
response carries a `FastByteArrayOutputStream` that `reset()` re-grows per flush (open issue #139).
At 10,000 concurrent in-flight responses, small per-response buffers multiply directly into RSS.

Netty-Loom still holds **3.1× less memory per connection than Tomcat+VT** (66.6 vs 206.8 KB) and a
887 MB resident set against 2,235 MB — but that advantage narrowed from 4.06× to 3.11× in eight days,
entirely because our own number moved. **This figure should not be published as a memory claim until
the increase is attributed.**

Tomcat-platform's low figure is not frugality: it caps at ~200 worker threads and queues the rest, so
its footprint does not scale with offered load in the first place.

---

## 7. Regression check against 2026-08-01

Every metric against the old sweep's two-pass 10k mean, judged against ±11%:

| Metric | Target | 2026-08-01 | 2026-08-09 | Δ | Inside ±11%? |
| --- | --- | ---: | ---: | ---: | --- |
| Scenario 1 throughput | Netty-Loom | 27,487 | 24,540 | −10.7% | ✅ (barely — see §3) |
| | Tomcat, platform | 29,076 | 29,715 | +2.2% | ✅ |
| | Tomcat, virtual | 28,631 | 28,791 | +0.6% | ✅ |
| Scenario 2 throughput | Netty-Loom | 45,857 | 44,584 | −2.8% | ✅ |
| | Tomcat, platform | 3,727 | 3,663 | −1.7% | ✅ |
| | Tomcat, virtual | 25,576 | 26,819 | +4.9% | ✅ |
| Scenario 2 p99 | Netty-Loom | 384.0 | 420.3 | +9.5% | ✅ |
| | Tomcat, platform | 2,794 | 2,830.5 | +1.3% | ✅ |
| | Tomcat, virtual | 2,666 | 2,408.9 | −9.6% | ✅ |
| Throughput/core | Netty-Loom | 18,650 | 20,336 | +9.0% | ✅ |
| | Tomcat, platform | 8,515 | 8,069 | −5.2% | ✅ |
| | Tomcat, virtual | 7,957 | 8,322 | +4.6% | ✅ |
| Memory per connection | Netty-Loom | 49.2 | 66.57 | **+35.3%** | ❌ |
| | Tomcat, platform | 44.6 | 44.08 | −1.2% | ✅ |
| | Tomcat, virtual | 199.8 | 206.78 | +3.5% | ✅ |

**Fourteen of fifteen inside the band.** The 73 commits — NL-37's streaming response path, NL-138's
new pipeline handler, NL-17's listener bracketing, NL-108's drain rework — cost no measurable
throughput, tail latency or CPU efficiency. Memory is the exception, and scenario 1 is the near-miss.

**A caveat that must travel with the null result on NL-37.** `NettyHttpServletResponse` only emits a
chunk once the body reaches `DEFAULT_BUFFER_SIZE` (8,192 bytes). `/ping` and `/work` return far less,
so **every response in this sweep took the buffered path** — a single `write(FullHttpResponse)`,
byte-identical to `ef72e89`. What this sweep measures from NL-37 is its *fixed per-dispatch overhead*,
not its streaming behaviour. Issues #139 (per-flush buffer re-grow) and #140 (flush per chunk) sit on
the path this workload never reaches, and are consequently **unmeasured here**. "Streaming is free" is
not a claim this sweep supports.

The headline ratios against Tomcat+VT all softened slightly, driven by Tomcat+VT posting its best
figures of the series:

| Ratio (Netty-Loom vs Tomcat+VT) | 2026-08-01 | 2026-08-09 |
| --- | ---: | ---: |
| Throughput | 1.79× | 1.66× |
| p99 | 6.94× | 5.73× |
| Throughput per core | 2.34× | 2.44× |
| Memory per connection | 4.06× | 3.11× |

Each individual move is inside ±11%; the consistent direction is worth noting but not claiming.

---

## 8. Defects — all three from the old §9 resolved

| §9 defect | Status |
| --- | --- |
| 1. `summarize.py` publishes aborted runs | **Fixed** (NL-110). `run-all.sh` records k6's exit code per scenario; `summarize.py` admits only `0` and `99`. 26 unit tests in `scripts/test-summarize.py`, all green. |
| 2. secured scenario all-or-nothing at scale | **Fixed** (NL-111). Transport failures retry; only server-answered rejections abort. All three secured runs completed at 10,000 VUs. |
| 3. Netty-Loom floods its log on shutdown | **Fixed** (NL-108 + NL-109), and verified here for the first time. |

Defect 3, measured:

| Target | 2026-08-01 | 2026-08-09 |
| --- | ---: | ---: |
| Netty-Loom | 81 MB (pass1), 100 MB (pass2) | **2,834 bytes** |
| Tomcat, platform | 2.9 KB | 2,996 bytes |
| Tomcat, virtual | 2.9 KB | 2,996 bytes |

`netty-loom.server.log` contains **zero** `RejectedExecutionException`, **zero**
`IllegalStateException: The servlet context has been closed`, and zero WARN or ERROR lines across 24
total lines — and is now *smaller* than either Tomcat's. NL-109 predicted ~1.5 MB by collapsing each
abandoned request's two stack traces into one WARN; the measured result is ~500× better than that,
because NL-108 removed the cause rather than the symptom: the drain now waits for in-flight
dispatches, so no requests are abandoned to log about.

**No new harness defects found.** The gate behaved correctly on nine clean runs; it was not exercised
against a failing run in this sweep, so its refusal path remains covered only by its unit tests.

---

## 9. Not re-tested in this sweep

Stated explicitly so no reader assumes a single pass reconfirmed the old document:

- **Crossover / order-independence.** No reversed pass. Run-order effects cannot be separated from
  real change — which is exactly what leaves §3's scenario-1 signal unresolved.
- **The 2,000-VU concurrency-dependence result.** The old sweep's most important finding — that
  Netty-Loom and Tomcat+VT are statistically indistinguishable at 2,000 connections and the wedge
  opens only as connections climb — has **no data point here**. It stands on the old sweep alone.
- **Flat throughput-per-core across concurrency levels.** The old claim (~18,200–19,000 from 2,000 to
  10,000 VUs, while Tomcat+VT's halves) requires both load levels. This sweep confirms only the 10k
  half; the flatness is not re-established.
- **The Security-starter memory A/B** the old §8 called for. Still not run, and §6 now needs it more.

---

## 10. What can be claimed

Supported by this sweep:

- At **10,000** concurrent blocking connections, Netty-Loom delivers **~1.7× the throughput and
  ~5.7× better p99** than Tomcat with virtual threads, on **~2.4× better throughput-per-core** and
  **~3.1× less memory per connection**, at **0.00%** errors.
- **Behind Spring Security at 10,000 connections, Netty-Loom leads Tomcat+VT by ~1.7× throughput and
  ~7.0× p99**, with all 10,000 VUs authenticated and zero failed checks. This is the first valid
  measurement of that scenario.
- **The Security filter chain costs this library no more than it costs Tomcat+VT** (−13.1% vs −16.3%
  throughput, inside noise) — issue #27's question, answered at 10k this time.
- **The 73 commits since `ef72e89` cost no measurable throughput, tail latency or CPU efficiency.**
- **The shutdown log flood is fixed**: 100 MB → 2.8 KB, now below both Tomcat targets.

Not supported, and should not be claimed:

- **Any memory-per-connection figure.** It rose 35% in eight days and 3.3× since June, unattributed.
- **Any per-request speed advantage** — Netty-Loom is 19–23% slower on trivial `/ping` in this run,
  the widest that gap has been measured.
- **That NL-37's streaming path is free.** It was not exercised; only its fixed per-dispatch cost was.
- **Anything at 2,000 connections or below**, and any claim that per-core efficiency is *flat across*
  concurrency levels — neither was measured here.
- **The ~3.3× advantage** implied by the committed 2026-06-13 snapshot. This sweep says ~1.7×, and
  agrees with 2026-08-01's ~1.8× that the committed figure overstates it (open issue **#113**).
- Anything resting on run order, since there was no reversed pass.

## Follow-ups this sweep argues for

None were actioned; this sweep was measurement only.

1. **Attribute the memory increase** (§6) — filed as **#144**. Two unexplained increments now,
   20.17 → 49.2 → 66.6 KB. The A/B with and without the Security starter that 2026-08-01 §8 asked for,
   plus a second A/B across NL-37, would separate them. NMT is already enabled by the harness, so the
   heap/stack/direct split costs nothing to obtain.
2. **Attribute the tail latency** (§3, §2) — filed as **#145**. p95 345 ms and p99 420 ms against a
   50 ms service time, while the server uses 2.19 of 8 cores, leaves ~100 ms per request in neither
   the sleep nor server CPU. Moving k6 off-box is the first disqualifier; if the tail collapses there,
   the harness owns the number rather than the server.
3. **Confirm or clear the scenario-1 per-request cost** (§3). A reversed pass is the cheap test; if it
   holds, the four candidate sources are individually measurable.
4. **Update the committed numbers.** `netty-loom-spring-benchmarks/results/SNAPSHOT.md` is still dated
   2026-06-13 and `README.md` quotes it, including the ~3.3× claim two sweeps have now contradicted.
   That is issue **#113**, still open, and this sweep is fresh evidence for it.

## Reproducing

```bash
./gradlew :netty-loom-spring-example-netty:bootJar :netty-loom-spring-example-tomcat:bootJar
cd netty-loom-spring-benchmarks
VUS=10000 DURATION=60s RAMP=15s SETTLE=35 \
  RESULTS_DIR=../docs/benchmarks/2026-08-09/pass1-forward bash scripts/run-all.sh
```

`*.log` files are **not tracked** — the root `.gitignore` carries a blanket `*.log`.

**The raw artifacts of this sweep did not survive either.** Between generation and commit, everything
in `pass1-forward/` except seven `*.summary.json` files was removed from the working copy by something
outside this repository — `SNAPSHOT.md`, `machine-load.txt`, all nine `.exit` files, all six memory
CSVs, and two of the nine summaries. The survivors' mtimes were rewritten in the same event, so this
was a sync or restore rather than a plain delete. The 2026-08-01 directory shows the identical
end state (`COMPARISON.md` alone), which is very likely the real reason that sweep's raw data is
"missing" rather than a decision not to commit it.

Consequences for reading this document:

- Every number here was transcribed from the generated `SNAPSHOT.md` and the summary exports **before**
  the loss, and the surviving summaries still back scenarios 1–3 for netty-loom and tomcat-platform
  plus tomcat-virtual's scenario 2.
- The **CPU and memory tables (§6) are no longer re-derivable** from committed data; their inputs were
  the deleted CSVs. Treat those two tables as the record of record.
- Anyone re-running should `git add` the results directory **immediately** on completion, or write
  `RESULTS_DIR` to a path outside this working copy.
