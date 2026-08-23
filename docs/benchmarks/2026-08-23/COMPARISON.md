# Benchmark sweep, 2026-08-23 — first run on dedicated hardware

Three order-controlled passes at commit `e020b12`, 27 k6 runs, **all 27 exited 0**.

| Pass | Load | Order | Directory |
| --- | --- | --- | --- |
| 1 | 10,000 VUs | netty → tomcat-platform → tomcat-virtual | [`pass1-forward/`](pass1-forward) |
| 2 | 10,000 VUs | reversed | [`pass2-reversed/`](pass2-reversed) |
| 3 | 2,000 VUs | forward | [`pass3-2k-forward/`](pass3-2k-forward) |

Every 10,000-VU figure below is the **mean of passes 1 and 2**. They are quoted as a mean only
because the two agree to within 3% on 14 of 15 metrics (§8); no figure here rests on a single pass.

## 1. Method and caveats — read before the numbers

- **New hardware, and therefore a new baseline.** Every previous sweep ran on a contended Apple M1
  Pro laptop. This one ran on an idle, dedicated Xeon. **The two are not comparable**, and this
  document deliberately omits the "regression against the previous sweep" table that
  [2026-08-01](../2026-08-01/COMPARISON.md) and [2026-08-09](../2026-08-09/COMPARISON.md) both
  carry: judging a cross-hardware delta against a ±11% noise floor would be arithmetic, not
  evidence. Where an earlier *finding* is reproduced, that is said explicitly and the numbers are
  not mixed.
- **Half the physical cores, and older ones.** Xeon E3-1230 v3 (Haswell, 2013): **4 physical cores /
  8 threads**, against the M1 Pro's 8. Absolute throughput is lower here by construction. Ratios
  and throughput-per-core are what carry across.
- **Single 8-thread box.** k6 still contends with the server. Measured directly during a 2,000-VU
  run: k6 **3.53 cores** against the server's **2.37** — the load generator costs more than the
  server it measures. The box sits at 98–99% CPU (77 user / 22 sys) with a run queue of 14–28
  throughout every high-concurrency plateau. Absolute latencies are inflated; throughput-per-core is
  the discriminator, exactly as [the harness README](../../../netty-loom-spring-benchmarks/README.md)
  argues.
- **The box was genuinely quiet**, which the M1 never was. Pre-pass load average 0.41 against
  2026-08-09's 1.97, no WindowServer, no editor, nothing running but `hermes-agent` (0.3% CPU),
  `fail2ban` and `named`. Per-pass snapshots in each `machine-load.txt` — written by
  `scripts/collect-env.sh` this time rather than by hand.
- **Provenance is captured, not typed.** Each pass carries an `env-server.txt` recording CPU, RAM,
  kernel, governor, JDK, k6 version, commit and OS limits. All three are byte-identical.
- **Machine state was tuned, and the tuning is part of the result.** Governor `performance` (stock
  `schedutil` parks cores at 800 MHz and would ramp differently per target inside the measured
  plateau); `nofile` 200,000; `ip_local_port_range` widened to `1024 65535`; `tcp_tw_reuse=1`.
- **`SETTLE=35` was kept unchanged** from 2026-08-09 even though this box no longer needs it — 64.5k
  ephemeral ports and `tcp_tw_reuse` make 10k held + 10k draining comfortable. Changing the drain
  interval in the same sweep that changes the hardware would have added a second variable.
- **k6 v1.4.2, pinned.** `test-summarize.py` verified k6's exit-code mapping against exactly this
  version, and the harness's publication gate is built on it. Upgrading the load generator in a
  sweep that already changes hardware would confound the one component that decides what gets
  published.
- **Noise floor ±11%**, as encoded in `summarize.py`. Nothing inside that band is reported as a
  result.
- **Coordinated omission is not corrected.** Scenarios 2 and 3 are closed-model (`ramping-vus`), so
  a saturated server throttles its own offered load and reads as "slow" rather than "overloaded".

## 2. Headline

At **10,000 concurrent blocking connections**, mean of the forward and reversed passes:

| Metric | Netty-Loom | Tomcat + virtual threads | Tomcat + platform threads |
| --- | ---: | ---: | ---: |
| Throughput (req/s) | **24,076** | 17,831 (1.35×) | 3,970 (6.07×) |
| Throughput per core (req/s/core) | **9,239** | 5,297 (1.74×) | 5,963 (1.55×) |
| p99 latency (ms) | **735** | 2,427 (3.30×) | 2,542 (3.46×) |
| CPU used (of 8 threads) | 2.61 | 3.37 | 0.67 (pool-capped) |
| Error rate | 0.00% | 0.00% | 0.00% |

**Netty-Loom serves 35% more requests than Tomcat+VT while burning 23% less CPU.** That combination
is what makes the per-core figure 1.74× rather than a rounding artifact of who grabbed more cores.

**Where the advantage does not exist.** At **2,000** connections Netty-Loom and Tomcat+VT are
indistinguishable: 23,400 vs 22,225 req/s (**+5.3%, inside the ±11% noise floor**), p99 163 vs
173 ms. This reproduces the 2026-08-01 finding on new hardware, independently.

**The mechanism, measured across both load levels.** This is the sweep's most useful result, because
it explains the headline rather than just asserting it:

| Throughput per core | 2,000 VUs | 10,000 VUs | change |
| --- | ---: | ---: | ---: |
| Netty-Loom | 9,285 | 9,239 | **−0.5%** (flat) |
| Tomcat + virtual threads | 8,334 | 5,297 | **−36.4%** |
| Tomcat + platform threads | 6,336 | 5,963 | −5.9% (pool-capped throughout) |

Netty-Loom's per-request cost is **flat** as connections rise 5×. Tomcat+VT's rises by half. The two
architectures are equally efficient at 2,000 connections and diverge only as the connection count
climbs — so the advantage is not "Netty is faster", it is "Netty does not get more expensive per
request as connections scale". 2026-08-01 asserted this from a single sweep; it is now confirmed
under order control on different hardware.

## 3. Scenario 1 — low concurrency (1→10 VUs, `GET /ping`)

Mean per-request latency across all three passes:

| Target | mean latency | vs best | throughput (pass 1 / 2 / 3) |
| --- | ---: | ---: | --- |
| Netty-Loom | 0.1899 ms | +7.4 µs | 17,640 / 17,533 / 17,694 |
| Tomcat, platform threads | 0.1913 ms | +8.8 µs | 17,888 / 17,357 / 17,552 |
| Tomcat, virtual threads | **0.1825 ms** | — | 18,134 / 18,150 / 17,999 |

There is **no per-request speed advantage to claim** — on trivial requests Netty-Loom is 7.4 µs
slower than Tomcat+VT, about 4%. Two honest refinements to how this has been reported before:

- The gap is **much narrower here than the 28 µs measured on the M1** (a claim this sweep cannot
  test directly, since the hardware differs — noted, not explained).
- Netty-Loom is **not the slowest of the three** on this hardware. It beats Tomcat-platform by
  1.4 µs. The README's "slowest of the three" phrasing was true of the M1 sweep and is not true
  here; both differences are small enough that only the direction, not the size, is worth carrying.

## 4. Scenario 2 — high-concurrency blocking I/O (10,000 VUs, `GET /work`)

| Target | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | Error rate |
| --- | ---: | ---: | ---: | ---: | ---: |
| Netty-Loom | 24,076 | 321 | 663 | 735 | 0.00% |
| Tomcat, platform threads | 3,970 | 2,512 | 2,535 | 2,542 | 0.00% |
| Tomcat, virtual threads | 17,831 | 147 | 1,991 | 2,427 | 0.00% |

The 2026-08-09 shape reappears intact: **Tomcat+VT's p50 is better than Netty-Loom's** (147 vs
321 ms) **while its p99 is 3.3× worse**. A fast median over a collapsing tail. Tomcat-platform
reports 0.00% errors because it queues rather than refuses — its 200-thread pool is the ceiling, and
its p99 is the honest signal, not its error rate.

## 5. Scenario 3 — behind Spring Security (10,000 VUs, `GET /work-secured`)

| Target | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | Error rate |
| --- | ---: | ---: | ---: | ---: | ---: |
| Netty-Loom | 18,627 | 392 | 777 | 891 | 0.00% |
| Tomcat, platform threads | 3,958 | 2,513 | 2,528 | 2,534 | 0.00% |
| Tomcat, virtual threads | 13,794 | 150 | 2,795 | 3,472 | 0.00% |

All three authenticated all 10,000 VUs with zero failed logins in both passes. Each VU logs in once
through `formLogin()` (with CSRF) and rides its session cookie; login requests are tagged separately
and excluded.

### Security overhead (scenario 2 → 3, same target, same run)

| Target | Δ throughput @10k | Δ p99 @10k | Δ throughput @2k |
| --- | ---: | ---: | ---: |
| Netty-Loom | −22.7% | +21.3% | −17.2% |
| Tomcat, platform threads | −0.3% | −0.3% | −0.2% |
| Tomcat, virtual threads | −22.7% | +43.2% | −17.5% |

**The filter chain costs the two virtual-thread targets exactly the same** — −22.7% against −22.7%
at 10k, −17.2% against −17.5% at 2k. Whatever Spring Security costs, it is not a Netty-Loom-specific
cost. (Tomcat-platform's ≈0% is not efficiency: it is already pool-saturated at 3,9xx req/s, so the
filter chain has no headroom left to consume.)

Read the Δ with run order in mind: scenario 3 always runs second, on a JVM that has just served the
scenario-2 plateau.

## 6. Resource efficiency

| Target | Throughput (req/s) | CPU (avg cores) | util % of 8 | Throughput / core |
| --- | ---: | ---: | ---: | ---: |
| Netty-Loom | 24,076 | 2.61 | 32.6% | **9,239** |
| Tomcat, platform threads | 3,970 | 0.67 | 8.3% | 5,963 |
| Tomcat, virtual threads | 17,831 | 3.37 | 42.1% | 5,297 |

`CPU (avg cores)` = Δ(cumulative CPU time) / Δ(wall clock) over the load window, so it excludes
startup and JIT. Server process only; k6 is separate.

### Memory per connection — still unresolved, still do not quote

| Target | Δ RSS median (MB) | Memory / connection (KB) | forward vs reversed |
| --- | ---: | ---: | ---: |
| Netty-Loom | 680.3 | 69.66 | 2.9% |
| Tomcat, platform threads | 429.4 | 43.97 | 1.8% |
| Tomcat, virtual threads | 2,156.7 | 220.84 | 0.3% |

The one thing this sweep adds is **reproducibility**: the figures agree to within 3% under order
reversal, so they are stable measurements rather than noise. Netty-Loom's 69.66 KB also sits close
to the M1's 66.57 KB, which suggests the metric is a property of the code and not of the box. It
remains unattributed and unpublishable until
[#144](https://github.com/azholdaspaev/netty-loom-spring/issues/144) resolves it.

## 7. Crossover agreement (pass 1 vs pass 2)

The control 2026-08-09 lacked. Same load, same box, reversed target order:

| Metric | worst disagreement |
| --- | ---: |
| All three scenarios' throughput, all three targets | ≤ 2.97% |
| Scenario 2 p99, all three targets | ≤ 2.15% |
| Scenario 3 p99, Netty-Loom and Tomcat-platform | ≤ 1.04% |
| Scenario 3 p99, **Tomcat + virtual threads** | **+11.67%** |

**14 of 15 metrics agree within 3%** — against the 2026-08-01 sweep's "every metric within 7%" on
the noisy M1. Run order is not driving anything reported here.

The single exception is Tomcat+VT's secured p99 (3,280 → 3,663 ms), just outside the ±11% floor.
That is the most volatile cell in the sweep — the collapsing tail of the worst-behaved
target-scenario pair — so no conclusion in this document rests on its exact value. The *direction*
(Tomcat+VT's secured tail is by far the worst measured) is identical in both passes.

## 8. Defects and findings

- **Off-box load generation from the development Mac is not viable at 10,000 VUs, for a reason
  outside the server.** A 10,000-VU probe over the 77 ms link produced **23,238 request failures
  (11.16%), all `connection refused`, and 2,972 req/s while the server sat 95% idle** (0.52 of 8
  cores). The same ramp on loopback completes at 22,677 req/s with 0.00% errors.

  **The cause is client- or path-side, not server-side**, and the server-side explanations are ruled
  out by measurement rather than argument:

  | Hypothesis | Evidence against |
  | --- | --- |
  | Accept-queue overflow (`SO_BACKLOG=128`) | `TcpExtListenOverflows` = **250 cumulative over 253 days of uptime** — cannot account for 23,238 failures in 60 s. And `tcp_abort_on_overflow=0`, so an overflow drops the ACK silently; it does not send the RST that produces `connection refused` |
  | Firewall / fail2ban | Zero `ufw.log` entries for the client IP; neither jail (`sshd`, `3x-ipl`) covers 18080 |
  | conntrack exhaustion | `nf_conntrack_count` 17 against a max of 262,144; no "table full" in `dmesg` |
  | Server death | Server log shows it serving normally throughout and past the end of the run |

  The residual explanation is the client host or the NAT/path between it and the server — 10,000
  simultaneous outbound connections to one destination is a load few consumer network paths hold.
  **This is a finding about the harness's environment, not about Netty-Loom.**

  What it does *not* establish: anything about `SO_BACKLOG`. That value is hardcoded to 128 at
  `NettyServer.java:136` and documented as a fixed limit
  ([#42](https://github.com/azholdaspaev/netty-loom-spring/issues/42)); this sweep produced **no
  evidence** that it is a bottleneck at any load level. It is worth revisiting on its own merits —
  128 is *below* Netty's own default of `NetUtil.SOMAXCONN` — but not on the strength of anything
  measured here.

- **The load generator costs more than the server on this box** (3.53 cores vs 2.37 at 2,000 VUs).
  Not a defect, but it bounds what the absolute numbers mean.

## 9. Not tested in this sweep

- **No two-host measurement.** Every number in this document is single-box loopback. `REMOTE_HOST`
  was added to `run-all.sh` and verified end to end at 50 VUs — correct remote launch, teardown,
  streamed memory sampling, reversed order, and provenance captured from the *server's* host rather
  than the client's — but it was not used for this sweep, and at 10,000 VUs it fails for the reason
  in §8. The two-host configuration the README calls "the only fully defensible one" therefore
  remains unmeasured: it needs a load generator that can hold 10,000 WAN connections, which the
  development Mac and its network path demonstrably cannot.
- **No open-model run.** Coordinated omission is uncorrected.
- **No memory A/B** against the Security starter or NL-37's response buffering (#144).
- **No comparison against the M1 sweeps**, deliberately. See §1.

## 10. What can be claimed

- At 10,000 blocking connections on this hardware, Netty-Loom serves **1.35× the throughput of
  Tomcat+VT on 23% less CPU**, for **1.74× the throughput per core** and a **3.3× better p99**, at
  an equal 0.00% error rate. Order-controlled.
- **Throughput per core is flat for Netty-Loom from 2,000 to 10,000 connections (−0.5%) and falls
  36.4% for Tomcat+VT.** This is the structural claim, and it is now confirmed on two different
  machines.
- At **2,000** connections the two are indistinguishable (+5.3%, inside the noise floor).
- Spring Security's filter chain costs Netty-Loom and Tomcat+VT the **same** −22.7%.
- The harness's own reproducibility: 14 of 15 metrics within 3% under order reversal.

## What cannot be claimed

- **Any comparison with the 2026-08-01 or 2026-08-09 numbers.** Different CPU, different core count.
- **Any per-request speed advantage.** Netty-Loom is 7.4 µs *slower* than Tomcat+VT at low
  concurrency (§3).
- **Any absolute throughput ceiling.** The box saturates at 98–99% CPU with k6 taking the larger
  share; every absolute figure is depressed by an unknown amount.
- **Memory per connection** (#144).
- **Anything about behaviour over a real network.** §8 is the only off-box datapoint and it measures
  the accept backlog, not the server.

## Reproducing

```bash
git clone --branch NL-01-perf https://github.com/azholdaspaev/netty-loom-spring.git
cd netty-loom-spring && git checkout e020b12
./gradlew :netty-loom-spring-example-netty:bootJar :netty-loom-spring-example-tomcat:bootJar
cd netty-loom-spring-benchmarks
ulimit -n 200000
for pass in "pass1-forward forward 10000" "pass2-reversed reversed 10000" "pass3-2k-forward forward 2000"; do
  set -- $pass
  VUS=$3 DURATION=60s RAMP=15s SETTLE=35 ORDER=$2 \
    RESULTS_DIR="$HOME/bench-results/2026-08-23/$1" bash scripts/run-all.sh
  sleep 60
done
```

Machine state applied before the sweep — governor `performance`, `nofile` 200000,
`ip_local_port_range 1024 65535`, `tcp_tw_reuse=1` — is recorded in each pass's `env-server.txt`.

**The raw artifacts of this sweep did survive**, unlike the previous two: `RESULTS_DIR` was written
outside the working copy and the results were `git add`ed on completion. Anyone re-running should do
the same. `*.k6.log` and `*.server.log` are excluded by the root `.gitignore`'s blanket `*.log`;
everything `summarize.py` reads is committed.
