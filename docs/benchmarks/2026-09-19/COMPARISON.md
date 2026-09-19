# Memory sweep, 2026-09-19 — what memory per connection is made of

Twenty-one single-target runs at 10,000 connections on the M1 Pro, all k6 exit 0, for
[#144](https://github.com/azholdaspaev/netty-loom-spring/issues/144): four commits, a Security
A/B, an idle-connection scenario and a Tomcat control, each in forward and reversed order, then six
profiling runs with a JFR recording, a virtual-thread dump and two class histograms.

| Pass | Runs | Order | Captures |
| --- | --- | --- | --- |
| A | 01–07 | tomcat-platform → head → head-nosec → e020b12 → 734ef88 → ef72e89 → head-idle | NMT, heap, threads |
| B | 08–14 | reversed | NMT, heap, threads |
| P | 15–20 | head, head-idle, head-nosec, e020b12, 734ef88, ef72e89 | + JFR, VT dump, histograms |
| protocol-check | 2 | head, tomcat-platform, with scenario 1 before scenario 2 | RSS only |

The raw captures are not committed; every number derived from them is in this document, and the
appendix holds the captures it quotes. `env-server.txt` is the provenance block, identical for
every run except the commit line; `sweep.txt` is the driver's ledger with start times, k6 exit
codes, established-connection counts and warning counts per run.

## 1. Method and caveats — read before the numbers

- **The question is the metric, not the throughput.** Each run is one target under one scenario:
  `high-concurrency.js` (10,000 VUs each holding one blocked `GET /work`), or `idle-connections.js`
  (10,000 VUs each holding one keep-alive connection with nothing in flight). Flags, ramp, hold and
  the memory sampler are `run-all.sh`'s, and `summarize.py`'s formula applies unchanged: memory per
  connection = (median RSS over scenario 2 − median idle RSS) / 10,000.
- **Tags.** `head` is `2cb7070` (`main` on 2026-09-19); `head-nosec` is the same commit with
  `spring-boot-starter-security` and `BenchmarkSecurityConfig` removed from the example app (its
  `env-server.txt` says `(dirty)` for that reason); `e020b12`, `734ef88` and `ef72e89` are the
  commits the [2026-08-23](../2026-08-23/COMPARISON.md), [2026-08-09](../2026-08-09/COMPARISON.md)
  and [2026-08-01](../2026-08-01/COMPARISON.md) sweeps measured, built from a clean checkout of each.
- **Same laptop as the two August M1 sweeps, different OS.** Apple M1 Pro, 16 GiB, Temurin 25.0.2,
  k6 v1.4.2 — and macOS 27.0 where 2026-08-09 ran on Darwin 25.5. §8 shows this matters.
- **The box was under memory pressure the whole time.** At the end of the sweep the compressor held
  803,103 pages (3.1 GiB) and swap 2.6 GiB, with k6 alone at 2.7 GiB resident. macOS's `ps rss`
  excludes compressed pages: sampled once during a plateau, the server reported 774 MB resident
  against an 849 MB physical footprint with 116 MB compressed (`top`'s `CMPRS`). `run-all.sh`'s
  metric is built on `ps rss`; on this box, today, it is measuring the compressor as much as the
  JVM. This is not a caveat to skip past — it is half of the finding.
- **The noise floor here is not ±11%.** The Tomcat control disagreed with itself by 31.4% between
  the two orders (§3) where the dedicated box managed 1.8%. Nothing below that spread is reported
  as a difference between commits.
- **Profiling runs are not RSS runs.** Runs 15–20 carry a JFR recording, a virtual-thread dump, a
  JFR heap walk and two class histograms, each of which forces a full GC or a safepoint. Their RSS
  columns are listed in the ledger for completeness and used for nothing; their histograms and
  recordings are what §6–§7 rest on.
- **The parked-request count in a profiling run is a sample, not a plateau value.** The thread dump
  and the histogram were taken right after other `jcmd` captures had stopped the JVM, when the
  sleeps that expired during the pause had just completed, so they see anywhere from 32 to 9,889
  requests in flight (ledger). The JFR recording's `jdk.ThreadSleep` events give the true
  distribution (§4).
- **Not measured:** Tomcat with virtual threads, the 2026-06-13 commit the issue's first row cites
  (no jar was built for it), the dedicated box, and any connection count but 10,000.

## 2. Run ledger

`in-flight` is the number of virtual threads parked in `BenchmarkController.work` in the thread
dump, profiling runs only. `warn` counts `WARN|ERROR` lines in the server log; four of them are the
JDK's native-access warnings printed at startup by every Netty-Loom jar.

| Run | Pass | Tag | k6 exit | ESTABLISHED | in-flight | Idle RSS med (MB) | Loaded RSS med (MB) | Loaded peak (MB) | Δ RSS (MB) | KB / conn | warn |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 00 | W | head (2,000 VUs, discarded) | 0 | 2,000 | | 195.4 | 434.6 | 442.1 | 239.2 | 24.49 | 4 |
| 01 | A | tomcat-platform | 0 | 10,000 | | 188.8 | 394.5 | 459.6 | 205.6 | **21.06** | 0 |
| 02 | A | head | 0 | 10,000 | | 230.1 | 457.6 | 620.4 | 227.5 | **23.30** | 4 |
| 03 | A | head-nosec | 0 | 10,000 | | 205.8 | 444.6 | 652.9 | 238.8 | **24.46** | 4 |
| 04 | A | e020b12 | 0 | 10,000 | | 224.1 | 512.3 | 710.7 | 288.2 | **29.51** | 4 |
| 05 | A | 734ef88 | 0 | 10,000 | | 232.5 | 504.6 | 635.5 | 272.0 | **27.86** | 4 |
| 06 | A | ef72e89 | 0 | 10,000 | | 229.6 | 482.1 | 632.1 | 252.5 | **25.86** | 4 |
| 07 | A | head-idle | 0 | 10,000 | | 224.4 | 262.9 | 275.6 | 38.4 | **3.93** | 4 |
| 08 | B | head-idle | 0 | 10,000 | | 214.8 | 263.4 | 268.3 | 48.6 | **4.97** | 4 |
| 09 | B | ef72e89 | 0 | 10,000 | | 232.3 | 477.2 | 693.7 | 244.9 | **25.08** | 4 |
| 10 | B | 734ef88 | 0 | 10,000 | | 224.5 | 437.7 | 622.0 | 213.2 | **21.84** | 4 |
| 11 | B | e020b12 | 0 | 10,000 | | 219.4 | 504.5 | 606.1 | 285.1 | **29.19** | 4 |
| 12 | B | head-nosec | 0 | 10,000 | | 216.7 | 460.3 | 706.0 | 243.6 | **24.94** | 4 |
| 13 | B | head | 0 | 10,000 | | 231.1 | 434.8 | 630.6 | 203.8 | **20.87** | 4 |
| 14 | B | tomcat-platform | 0 | 10,000 | | 228.1 | 498.3 | 515.0 | 270.2 | **27.67** | 0 |
| 15 | P | head | 0 | 10,000 | 604 | 241.6 | 518.8 | 725.8 | 277.2 | (28.38) | 4 |
| 16 | P | head-idle | 0 | 10,000 | 0 | 237.7 | 238.1 | 305.6 | 0.4 | (0.04) | 4 |
| 17 | P | head-nosec | 0 | 10,000 | 1,931 | 190.0 | 691.4 | 872.7 | 501.4 | (51.35) | 4 |
| 18 | P | e020b12 | 0 | 10,000 | 32 | 226.0 | 899.6 | 1,122.2 | 673.6 | (68.98) | 5 |
| 19 | P | 734ef88 | 0 | 10,000 | 391 | 230.4 | 626.9 | 725.0 | 396.5 | (40.61) | 9 |
| 20 | P | ef72e89 | 0 | 10,000 | 379 | 232.9 | 667.9 | 844.5 | 435.0 | (44.54) | 5 |

Runs 18–20 logged one, five and one `KQueueIoHandler: Unexpected exception in the selector loop`
(`kevent(..) failed with error(-22): Invalid argument`) during the plateau. The same three jars
logged none in their four measured runs, and every k6 threshold held, so it is recorded here and
not pursued: it appeared only under the profiling captures and only on commits `main` has moved past.

## 3. Commit series — the published increments do not reproduce

| Tag | A | B | mean | A vs B | published | this sweep vs published |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ef72e89 | 25.86 | 25.08 | **25.47** | −3.0% | 49.2 (2026-08-01, M1) | −48% |
| 734ef88 | 27.86 | 21.84 | **24.85** | −21.6% | 66.57 (2026-08-09, M1) | −63% |
| e020b12 | 29.51 | 29.19 | **29.35** | −1.1% | 69.66 (2026-08-23, Xeon) | −58% |
| head | 23.30 | 20.87 | **22.08** | −10.4% | — | |
| head-nosec | 24.46 | 24.94 | **24.70** | +2.0% | — | |
| tomcat-platform (control) | 21.06 | 27.67 | **24.36** | +31.4% | 44.08 / 43.97 | −45% |
| head-idle | 3.93 | 4.97 | **4.45** | +26.4% | — | |

Three things the table settles:

- **The comparability gate fails for every commit, and for the control.** The same three jars that
  published 49.2, 66.57 and 69.66 KB measure 25, 25 and 29 KB today, and Tomcat-platform — whose
  code did not change — halves with them. Whatever moved between the sweeps, it was not in these
  jars.
- **The 2026-08-01 → 2026-08-09 increment (+35.3%) is cleared.** `ef72e89` and `734ef88` are 2.4%
  apart, inside any noise floor, in both orders.
- **`e020b12` → `head` is −25% in both orders**, the only pair whose difference exceeds the
  control's own 31% spread in one of them, and coincident with #51 taking `HttpObjectAggregator`
  out of the pipeline. It is a direction, not a figure: §7 shows what #51 removed per in-flight
  request, and that it is small.

## 4. Idle connections against blocked requests

An open keep-alive connection with nothing in flight costs **4.45 KB** of RSS (runs 07 and 08).
The same connection carrying one blocked `/work` request costs 22.08 KB at `head`. So 80% of
scenario 2's figure is not the connection at all. The rest of this document is about what it is.

**How many of the 10,000 requests are in the server at once.** Every `/work` dispatch sleeps once,
so the number of overlapping `jdk.ThreadSleep` events in the profiling recordings is the number of
requests parked in the controller at that instant (`sleep-concurrency.txt`, sampled every 10 ms
over the recording):

| Run | sleep events | mean parked | median | p10 | p90 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 15 P head | 2,332,291 | **2,241** | 1,972 | 655 | 4,112 | 9,745 |
| 17 P head-nosec | 2,440,539 | 2,265 | 2,096 | 723 | 3,980 | 9,864 |
| 18 P e020b12 | 2,462,233 | 2,237 | 2,076 | 722 | 3,986 | 9,627 |
| 19 P 734ef88 | 2,388,632 | 2,238 | 2,155 | 715 | 3,786 | 9,588 |
| 20 P ef72e89 | 2,443,914 | 2,219 | 2,210 | 751 | 3,723 | 9,330 |

At ~40,000 req/s and a 50 ms sleep, Little's law puts the parked population at ~2,200 and the
recordings agree on every commit. The other ~7,800 connections are idle from the server's point of
view: their request is in k6, in the loopback, or in the event loop's read queue. The
"1 VU = 1 blocked request" premise in `high-concurrency.js`'s header holds only at the p99 of this
distribution; what scenario 2 measures per connection is one fifth of a parked request plus a
connection.

## 5. Security A/B — cleared

`head-nosec` measures 24.70 KB against `head`'s 22.08 KB: +5% in order A, +19.5% in order B, both
inside the control's 31% spread, and in the direction that says *removing* Security costs memory.
The starter is cleared as a cause of any increment.

What the filter chain does cost is visible where it lives. A request parked in the controller
carries **46 frames with Security and 35 without** (the thread dumps of runs 15 and 17): the eleven frames are `DelegatingFilterProxy` → `FilterChainProxy` → `CompositeFilter`, which
Boot registers at `/*` and which `securityMatcher` does not remove from the path of `/work`. They
cost 176 bytes of stack chunk per parked request (2,640 against 2,464 bytes) and, with the
`SecurityContext` holder and request wrapper allocations, 1.6 KB of allocation per request (16.2
against 14.6 KB, §7). Neither is a memory-per-connection story.

## 6. Native memory — the whole delta is one G1 heap

`nmt-plateau.diff.txt` is `jcmd VM.native_memory summary.diff` against a baseline taken after the
idle samples, 45 s into the hold. Committed deltas in MB; the categories not shown moved by 0–1 MB
in every run.

| Run | Java Heap | Thread | Other | GC | Code | Metaspace | Total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 01 A tomcat-platform | +334 | +4 | +2 | +9 | +5 | +2 | +358 |
| 02 A head | +568 | +0 | +10 | +13 | +6 | +2 | +601 |
| 03 A head-nosec | +476 | +0 | +10 | +11 | +10 | +2 | +511 |
| 04 A e020b12 | +474 | +0 | +10 | +14 | +6 | +2 | +511 |
| 05 A 734ef88 | +447 | +0 | +10 | +12 | +6 | +2 | +482 |
| 06 A ef72e89 | +374 | +0 | +10 | +9 | +6 | +2 | +403 |
| 07 A head-idle | −67 | +1 | +8 | −1 | +5 | +1 | −52 |
| 08 B head-idle | +38 | +1 | +8 | +1 | +4 | +1 | +55 |
| 09 B ef72e89 | +519 | +0 | +10 | +12 | +5 | +2 | +551 |
| 10 B 734ef88 | +392 | +0 | +10 | +10 | +7 | +2 | +423 |
| 11 B e020b12 | +567 | +0 | +10 | +13 | +6 | +2 | +601 |
| 12 B head-nosec | +454 | +0 | +10 | +10 | +6 | +2 | +485 |
| 13 B head | +401 | +0 | +10 | +10 | +6 | +2 | +431 |
| 14 B tomcat-platform | +333 | +4 | +2 | +9 | +4 | +2 | +356 |

- **Java Heap is 93–95% of every Netty-Loom delta**, and it is *committed* heap, not live heap:
  §7's histograms put the live set at 50 MB. Between baseline and plateau G1 committed 400–570 MB
  of young generation for an allocation rate of ~650 MB/s (§7) and, with no `-Xms`, RSS followed.
- **The committed heap does not go away when the connections do.** `nmt-after-close.diff.txt`,
  taken 5 s after k6 finished with `ESTABLISHED` at 0, still shows Java Heap at +374 to +568 MB in
  every measured run — the same numbers as at the plateau. Whatever this memory is, it was never
  per-connection state; a metric that reads it as such reads G1's sizing policy.
- **Thread is +0 MB.** 10,000 parked virtual threads add no native stacks. Tomcat-platform's +4 MB
  committed (+390 MB reserved) is its 200 platform workers.
- **Other is +10 MB with requests in flight and +8 MB idle** — Netty's pooled direct buffers, about
  1 KB per connection. This is the only line that scales with connections and does not vanish
  into the heap.
- **The idle scenario's heap delta is −67 and +38 MB.** With ~860 req/s of `/ping` there is no
  allocation pressure, G1 shrinks or grows within its jitter, and the 4.45 KB per connection of §4
  is the sum of the live objects in §7 plus the 8 MB of direct buffers.

## 7. Heap composition — what a connection and a request actually retain

`histogram-plateau.txt` is `jcmd GC.class_histogram` at the plateau (it forces a full GC, so the
totals are live bytes); `histogram-after-close.txt` is the same after every connection closed.

| Run | in-flight at histogram | live at plateau (MB) | live after close (MB) | per-connection classes (KB/conn) | per-request classes (KB/req) | stack chunk (bytes/req) | frames/req | allocation per request (KB) | req/s |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 15 P head | 283 | 50.6 | 17.9 | 1.62 | 3.90 | 2,640 | 46 | 16.2 | 37,728 |
| 16 P head-idle | 0 | 47.8 | 17.0 | 1.62 | — | — | — | — | 857 |
| 17 P head-nosec | 1,931 | 62.1 | 16.3 | 1.66 | 4.19 | 2,464 | 35 | 14.6 | 39,655 |
| 18 P e020b12 | 6,841 | 104.3 | 17.3 | 1.65 | 3.65 | 2,592 | 45 | 16.2 | 40,495 |
| 19 P 734ef88 | 2,247 | 64.0 | 17.4 | 1.63 | 4.25 | 2,360 | 45 | 15.9 | 39,499 |
| 20 P ef72e89 | 2,181 | 65.3 | 17.4 | 1.62 | 4.94 | 3,600 | 45 | 17.3 | 39,492 |

`in-flight at histogram` is the `jdk.internal.vm.StackChunk` count, one per parked request at the
instant of the full GC. `per-connection classes` sums the classes with exactly 10,000 instances;
`per-request classes` sums the classes with as many instances as stack chunks. `allocation per
request` is the sum of eden reclaimed over the last 30 s of young collections in `jfr-gc.txt`,
divided by that run's k6 request rate.

**A connection retains ~3.1 KB of heap** at `head`: 47.8 − 17.0 MB over 10,000 idle connections.
1.62 KB of it is 41 Netty and pipeline classes with one instance per channel (`HttpServerCodec`'s
decoder 120 B, `KQueueSocketChannel` 112 B, `ChannelOutboundBuffer`, the pipeline head and tail,
`HttpReadTimeoutHandler`'s scheduled future, `HttpRequestHandler` 48 B …), 576 B is nine
`DefaultChannelHandlerContext`s per channel, and the rest is the arrays behind them. Identical at
1.62–1.66 KB on every commit measured; `ef72e89` has eight contexts per channel where the later
commits have nine (#138's handler).

**A parked request retains ~6.5 KB**: a 2.6 KB stack chunk holding 46 frames, plus ~3.9 KB of
`NettyHttpServletRequest`, `NettyHttpServletResponse`, its `FastByteArrayOutputStream`,
`HttpRequestBodyStream`, `HttpChannelResponseWriter`, `DefaultHttpRequest` and its headers, the
`VirtualThread` and its continuation. Run 18 caught the other extreme with 6,841 chunks and 9,889
`VirtualThread`s live: (104.3 − 17.3 − 31) MB over 9,889 requests is 5.7 KB each, the same figure
from the other end. Before #51 each in-flight request also held an
`HttpObjectAggregator$AggregatedFullHttpRequest` and a `CompositeByteBuf` with its component
array (~200 B), and `ef72e89`'s stack chunks were 3.6 KB against 2.4–2.6 KB afterwards.

**So at the worst instant — all 10,000 requests parked at once — the live set is ~100 MB, or 10 KB
per connection.** The RSS delta the harness reports is two to seven times that, and §6 says where
the rest is: committed young generation.

**Allocation per request is 14.6–17.3 KB on every commit**, at ~40,000 req/s about 650 MB/s. The
top sites are the same everywhere (`jfr-allocation-by-site.txt`): the continuation yield itself
(15–25%, the stack chunk copy on every park), `StringLatin1.toLowerCase` (5–8%, header-name
normalisation), `HashMap.resize`/`newNode`, `DefaultHeaders`, `SpringHttpRequestDispatcher.handle`
(the servlet request/response pair), `FastByteArrayOutputStream.addBuffer` (the 256-byte ladder,
#139). None of it is retained past the response; all of it sizes eden.

**`jfr-pinned-threads.txt` is empty in every run**: no parked request ever held a carrier thread, so
no native stack enters the figure.

## 8. Verdict on the increments

The hypothesis this sweep set out to test — that the figure is dominated by the parked requests'
stack chunks and rose with stack depth — is **refuted**: a stack chunk is 2.6 KB and the whole
per-request retention 6.5 KB, and at the plateau only a fraction of the 10,000 requests are parked
at any instant. The increments are attributed instead to **the metric**:

1. **The RSS delta measures G1's committed young generation, not connection state.** §6: heap is
   93–95% of the delta, it is sized by a 650 MB/s allocation rate, and it stays committed after the
   last connection closes. That makes the figure a function of throughput, of `-Xmx` with no
   `-Xms`, of the JDK's sizing heuristics and of the JVM's history before the plateau —
   none of which are per-connection costs.
2. **The JVM's history alone moves the same jar by 72%.** The protocol check ran
   `run-all.sh`'s sequence — scenario 1's 30 s of `/ping` before scenario 2 — on the `head` jar and
   measured **38.10 KB** where the direct runs measured 22.08. Tomcat-platform under the same
   sequence measured 26.41 KB against its 24.36 KB mean: at 3,800 req/s it allocates too little
   for eden sizing to have anywhere to go, so its figure does not move. Every published sweep used
   that sequence; this one, deliberately, did not, and the absolute levels differ accordingly.
3. **The operating system's RSS accounting supplies the rest.** On macOS 27 under the memory
   pressure recorded in §1, `ps rss` excludes compressed pages and the samples swing between 400
   and 725 MB two seconds apart on a heap NMT reports as steady. The same three jars and the same
   control read half of their macOS 26 and Linux figures. The Linux box's 69.66 KB, reproducible
   to 3% there, is the honest value of the *RSS metric* for `e020b12`; it is just not a
   per-connection cost either.
4. **Per increment:** 2026-08-01 → 2026-08-09 (`ef72e89` → `734ef88`, +35.3% published) — cleared:
   −2.4% here in both orders, no change in retained per-connection or per-request state (§7).
   2026-06-13 → 2026-08-01 (20.17 → 49.2) — not measurable: no jar for the June commit; on the
   evidence above the June figure was the same metric at a lower allocation rate on a JVM with a
   different history, and nothing in §7 suggests a retained-state change of that size is possible
   in this code. `e020b12` → `head` — a −25% *decrease* of the RSS figure in both orders, matching
   #51's removal of the per-request aggregation objects, reported as direction only.

## 9. Not tested

- Tomcat with virtual threads, and any target on the dedicated box.
- Any connection count but 10,000, and any scenario but the two above.
- The 2026-06-13 commit.
- Why `18-P-e020b12` still reports Java Heap +884 MB committed after close where the other
  profiling runs shrank to +91–305 MB after their full GC; it changes nothing above.
- The `kevent` EINVAL warning of §2.

## 10. What can be claimed

- **A Netty-Loom connection retains about 4 KB**: 3.1 KB of heap in the channel, its pipeline and
  nine handler contexts, plus about 1 KB of pooled direct buffer — measured with 10,000 idle
  connections, identical to within 3% across the four commits from `ef72e89` to `2cb7070`.
- **A blocked request retains about 6.5 KB on top of that**, 2.6 KB of it the parked stack; no
  native thread stack, no pinning.
- **Neither number scales with throughput.** What scales with throughput is the committed young
  generation, which is what `run-all.sh`'s memory-per-connection figure has been reporting.
- The 2026-08-01 → 2026-08-09 increment was not a code change. The Security starter is not a cause.

## What cannot be claimed

- **Any memory-per-connection figure derived from RSS on this laptop**, in either direction — and
  the published 49.2, 66.57 and 69.66 KB are not per-connection figures on any machine.
- A per-commit ranking of the four jars on the RSS metric: the control's own spread is 31%.
- Anything about Tomcat with virtual threads.

## What follows

The harness metric needs replacing, not the code: a per-connection figure should be the live heap
after a forced collection at the plateau plus NMT's `Other`, read once per run, with the
thread dump taken before any other `jcmd` capture so the in-flight count is a plateau value. That
is a harness change and a separate issue; this sweep's `profile-memory.sh` already captures every
input it needs.

## Reproducing

```bash
git clone https://github.com/azholdaspaev/netty-loom-spring.git && cd netty-loom-spring
git checkout 2cb7070
./gradlew :netty-loom-spring-example-netty:bootJar :netty-loom-spring-example-tomcat:bootJar
# older commits: git worktree add ../wt-<sha> <sha> && (cd ../wt-<sha> && ./gradlew :netty-loom-spring-example-netty:bootJar -x test)
# head-nosec: same, after deleting the security dependency line in the example's build.gradle.kts and BenchmarkSecurityConfig.java
cd netty-loom-spring-benchmarks
R="$HOME/bench-results/2026-09-19"
for tag in head head-nosec e020b12 734ef88 ef72e89; do
  VUS=10000 DURATION=60s RAMP=15s SETTLE=25 bash scripts/profile-memory.sh netty-loom 18080 "$R/A-$tag" high-concurrency.js -jar "$HOME/jars/$tag.jar"
done
VUS=10000 bash scripts/profile-memory.sh netty-loom 18080 "$R/A-head-idle" idle-connections.js -jar "$HOME/jars/head.jar"
VUS=10000 bash scripts/profile-memory.sh tomcat-platform 18081 "$R/A-tomcat-platform" high-concurrency.js \
  -jar "$HOME/jars/tomcat.jar" --spring.profiles.active=platform --server.tomcat.max-connections=20000
# pass B: the same seven in reverse; pass P: PROFILE=1 for head, head-idle, head-nosec, e020b12, 734ef88, ef72e89
```

Each run directory then holds what `profile-memory.sh` writes: the two RSS CSVs, the k6 export
and exit code, `nmt-*.txt`, `heap-*.txt`, `netstat-*.txt`, `threads-plateau.txt`, and with
`PROFILE=1` `vthreads-plateau.txt`, `plateau.jfr`, `jfr-<view>.txt` and `histogram-*.txt`. The
`sleep-concurrency` figures of §4 come from
`jfr print --stack-depth 0 --events jdk.ThreadSleep plateau.jfr`, counting the events that overlap
each 10 ms instant.

## Appendix — the captures quoted above

### A. NMT at `head`, run 02 (measured, no JFR)

Committed deltas against the idle baseline; plateau on the left, 5 s after the last connection
closed on the right. The categories omitted moved by under 1 MB.

| Category | plateau | after close |
| --- | ---: | ---: |
| Java Heap | 630,784 KB (+581,632) | 630,784 KB (+581,632) |
| GC | 65,092 KB (+13,489) | 65,098 KB (+13,495) |
| Other | 14,602 KB (+9,984) | 14,602 KB (+9,984) |
| Code | 24,743 KB (+5,953) | 25,297 KB (+6,507) |
| Metaspace | 32,529 KB (+2,250) | 32,529 KB (+2,250) |
| Thread | 1,756 KB (+414) | 1,996 KB (+654) |
| Class | 5,757 KB (+230) | 5,765 KB (+237) |
| Total committed | 803,225 KB (+615,541) | 804,689 KB (+617,004) |

The idle scenario's profiling run, for contrast: Java Heap 92,160 KB (−64,512) and Other
12,588 KB (+7,970) at its plateau, total committed 275,446 KB (−21,738).

### B. Classes with one instance per connection at `head`, run 15

From `jcmd GC.class_histogram` at the plateau, every class with 9,900–10,100 instances, largest
first; 41 classes, 1,656 bytes per connection in total.

| Class | bytes/conn |
| --- | ---: |
| `io.netty.handler.codec.http.HttpServerCodec$HttpServerRequestDecoder` | 120 |
| `io.netty.channel.kqueue.KQueueSocketChannel` | 112 |
| `io.netty.channel.ChannelOutboundBuffer` | 64 |
| `io.netty.channel.DefaultChannelPipeline$HeadContext` | 64 |
| `io.netty.channel.DefaultChannelPipeline$TailContext` | 64 |
| `io.netty.util.concurrent.ScheduledFutureTask` | 64 |
| `io.netty.channel.kqueue.KQueueSocketChannelConfig` | 64 |
| `io.netty.channel.AdaptiveRecvByteBufAllocator$HandleImpl` | 56 |
| `io.netty.channel.AbstractChannel$CloseFuture` | 48 |
| `io.netty.channel.DefaultChannelPipeline` | 48 |
| `io.netty.channel.kqueue.KQueueIoHandler$DefaultKqueueIoRegistration` | 48 |
| `io.github.azholdaspaev.nettyloomspring.core.handler.HttpReadTimeoutHandler` | 48 |
| `io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestHandler` | 48 |
| `io.netty.buffer.UnpooledByteBufAllocator$InstrumentedUnpooledHeapByteBuf` | 48 |
| `io.netty.channel.kqueue.KQueueSocketChannel$KQueueSocketChannelUnsafe` | 48 |
| `io.netty.handler.codec.http.HttpServerCodec$HttpServerResponseEncoder` | 48 |
| `io.netty.channel.kqueue.KQueueIoEvent` | 40 |
| `io.netty.channel.kqueue.KQueueRecvByteAllocatorHandle` | 40 |
| `io.netty.handler.codec.http.HttpServerCodec` | 40 |
| `io.netty.util.internal.AdaptiveCalculator` | 40 |
| `io.netty.channel.AdaptiveRecvByteBufAllocator` | 32 |
| `io.netty.channel.DefaultChannelId` | 32 |
| `io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestBodyLimitHandler` | 32 |
| `io.netty.channel.CombinedChannelDuplexHandler$1` | 32 |
| `io.netty.handler.codec.http.HttpObjectDecoder$LineParser` | 32 |
| `io.netty.channel.SingleThreadIoEventLoop$IoRegistrationWrapper` | 24 |
| `io.netty.channel.SucceededChannelFuture` | 24 |
| `io.netty.channel.kqueue.BsdSocket` | 24 |
| `[Lio.netty.util.DefaultAttributeMap$DefaultAttribute;` | 24 |
| `io.github.azholdaspaev.nettyloomspring.core.handler.HttpDrainHandler` | 24 |
| `io.github.azholdaspaev.nettyloomspring.core.handler.HttpPipeliningHandler` | 24 |
| `io.github.azholdaspaev.nettyloomspring.core.handler.HttpReadTimeoutHandler$$Lambda` | 24 |
| nine further classes of 8–16 bytes | 106 |

Outside the exact-count set, `io.netty.channel.DefaultChannelHandlerContext` has 90,001 instances
(nine per channel, 576 bytes per connection) and the byte and object arrays behind the above make
up the rest of the 3.1 KB per connection.

### C. Allocation by site, top eight per profiling run

`jfr view allocation-by-site`, share of sampled allocation pressure.

| Site | head | head-nosec | e020b12 | 734ef88 | ef72e89 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `jdk.internal.vm.Continuation.doYield()` | 16.5% | 18.1% | 17.1% | 15.5% | 24.8% |
| `java.lang.StringLatin1.toLowerCase(...)` | 6.8% | 5.6% | 6.4% | 6.3% | 5.2% |
| `java.util.HashMap.resize()` | 5.5% | 4.6% | 5.1% | 4.9% | 4.9% |
| `java.util.HashMap.newNode(...)` | 4.2% | 3.8% | 4.0% | 3.6% | 3.4% |
| `java.lang.invoke.DirectMethodHandle.allocateInstance(...)` | 3.1% | 3.8% | 3.1% | 2.6% | 2.8% |
| `io.netty.handler.codec.DefaultHeaders.<init>(...)` | 2.5% | 2.4% | 2.2% | 2.4% | 2.5% |
| `...mvc.handler.SpringHttpRequestDispatcher.handle(...)` | 2.1% | 2.3% | — | — | 1.4% |
| `org.springframework.util.FastByteArrayOutputStream.addBuffer(int)` | 1.6% | 2.1% | 1.5% | 1.8% | 1.7% |
