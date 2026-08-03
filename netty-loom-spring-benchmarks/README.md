# netty-loom-spring-benchmarks

A reproducible [k6](https://k6.io/) benchmark answering the only question that decides whether
this library is worth adopting: **does running blocking Spring MVC controllers on Netty + virtual
threads actually beat a stock Tomcat starter under high concurrency — and does it beat Tomcat with
virtual threads simply switched on?**

If Tomcat-with-virtual-threads matches Netty-Loom, the honest recommendation is "set
`spring.threads.virtual.enabled=true`," not "adopt this library." This harness is built to surface
that outcome plainly rather than hide it.

> This is a plain directory of scripts, **not** a Gradle module — k6 is an external tool with no
> Java to compile, so wiring it into `./gradlew build` would only slow CI down. Run it explicitly.

## The three targets

All three run an **identical** Spring MVC app (same controller, same two endpoints):

| Target | Server | Port | Config |
|---|---|---|---|
| `netty-loom` | this library's starter (Netty + one virtual thread per request) | 18080 | `server.port=18080` |
| `tomcat-platform` | stock embedded Tomcat, classic thread-per-request | 18081 | `spring.threads.virtual.enabled=false` |
| `tomcat-virtual` | embedded Tomcat dispatching onto virtual threads | 18082 | `spring.threads.virtual.enabled=true` |

The apps live in [`netty-loom-spring-example-netty`](../netty-loom-spring-example-netty) and
[`netty-loom-spring-example-tomcat`](../netty-loom-spring-example-tomcat) (the Tomcat one is launched
twice with different Spring profiles, `platform` and `virtual`).

Both Tomcat targets run with `server.tomcat.max-connections` raised above the connection count
(`run-all.sh` sets it to `2 × VUS`). This is **not** tuning Tomcat to win — it removes a confound:
Tomcat's connector defaults to `max-connections=8192`, an established-connection cap that
`spring.threads.virtual.enabled` does **not** raise (the flag only swaps the request executor for a
`VirtualThreadExecutor`). Left at the default, ~1,800 of 10k connections would be reset/queued at the
accept ceiling — a config artifact masquerading as an architectural limit. See
[Interpreting results](#interpreting-results--and-how-to-keep-yourself-honest).

Endpoints:
- `GET /ping` → `pong` — minimal work, for raw throughput.
- `GET /work` → `Thread.sleep(50)` then small JSON — a **blocking** 50ms call simulating a database
  round-trip. This is the Loom scenario: a parked virtual thread is cheap; a parked platform thread
  pins an OS thread from a bounded pool (~200).
- `GET /work-secured` → the same 50ms sleep, behind Spring Security's `formLogin()` chain. Both apps
  scope `securityMatcher` to the secured paths, so `/ping` and `/work` stay outside the chain and
  scenarios 1 and 2 stay broadly comparable to snapshots taken before Security was added. Not
  *identical*, though: Boot registers `DelegatingFilterProxy` at `/*` regardless, so every request
  still pays proxy dispatch and `StrictHttpFirewall` validation before falling through. That cost is
  small but unmeasured, and it lands hardest on `/ping`, where per-request work is otherwise near
  zero.

## Scenarios

1. **Low-concurrency throughput** (`k6/low-concurrency.js`) — 1→10 VUs hammering `/ping`. Measures
   transport overhead and baseline latency where per-request work is negligible.
2. **High-concurrency blocking I/O** (`k6/high-concurrency.js`) — N VUs (default 10,000) each looping
   `GET /work`. With keep-alive on, **1 VU ≈ 1 persistent connection ≈ 1 in-flight blocked request**,
   which is what makes the server-side memory-per-connection measurement meaningful.
3. **High-concurrency behind Spring Security** (`k6/high-concurrency-secured.js`) — the same shape as
   scenario 2 against `/work-secured`. Each VU logs in once through the generated form (CSRF token
   scraped from the login page) and then replays its session cookie for the rest of the run, so the
   steady state measures an authenticated request through the whole filter chain rather than the
   login. The scenario-2 vs scenario-3 delta on the same target and run is what the chain costs.
   Every VU's login lands inside the ramp, so the run tolerates up to 1% of them failing at the
   transport level before `login_failed` fails it: one dead socket is weather, one VU in a hundred
   unable to authenticate is a plateau measured at the wrong connection count.

All three report **p50 / p95 / p99 latency** and **error rate**. Error rate is the only hard
threshold; latency is measured, not gated (measuring it is the point).

> **What the snapshot refuses to publish.** A row renders as `invalid` rather than as numbers when
> the run behind it cannot support them, and a comparative verdict built on such a row is replaced
> by *"Not answerable"*. Two gates, with different scopes.
>
> *Every scenario* is gated on k6 having run to the end. `run-all.sh` records k6's exit code beside
> each summary export (`<target>_<scenario>.exit`) and `summarize.py` publishes only `0` and `99`,
> the latter being a crossed threshold — a saturated platform-thread target is the finding this
> harness exists to report. Any other code means the run died mid-flight, most often
> `exec.test.abort()` (108). This gate is not optional decoration: k6 writes a complete-looking
> summary export for an aborted run, and a truncated run's check rate is *perfect by construction*,
> because checks are only ever evaluated on requests that were actually issued. Nothing inside the
> export reveals that it covers ten seconds of a sixty-second plateau. A missing `.exit` file counts
> as not-completed, so a results directory produced before this gate existed renders as `invalid`
> until it is re-run.
>
> *Scenario 3 alone* is gated a second time, on its correctness checks and a non-zero steady-state
> request count. If the VUs are not actually authenticated, every request becomes a cheap redirect
> to `/login` — *higher* throughput, *lower* latency, and 0% transport errors, because k6 counts a
> 302 as a successful response. It reads exactly like a win, and it exits cleanly, so the first gate
> never sees it. Three things stop it being published: the work request sets `redirects: 0`, so an
> unauthenticated request is recorded as the 302 it is rather than followed to the login page's 200;
> the `checks` threshold gates the run on every steady-state response being a real 200; and
> `summarize.py` refuses any target whose checks failed by more than the 1% that threshold itself
> allows — the fake win fails ~100% of its checks, so the tolerance costs nothing that matters and
> keeps a saturated target's stray check from silencing the comparison. Scenarios 1 and 2 are left out
> of this second gate — there a failed check means the server returned non-200 under load, which is
> the finding the error-rate column exists to report.
>
> The same gate covers *how many VUs authenticated*, which the check rate cannot see: a VU that never
> logs in issues no steady-state requests, so it leaves every check passing while quietly shrinking
> the plateau below the connection count the row claims. The scenario declares that tolerance as a
> `login_failed` threshold and `summarize.py` refuses a row whose threshold was crossed.
>
> Note also that k6 clears its cookie jar at the **start of every iteration** — the session id is
> therefore carried in a module-scoped variable and sent as an explicit header, not left to the jar.

`scripts/test-summarize.py` covers both gates (`python3 scripts/test-summarize.py`, stdlib only).

## What gets measured, and why these three metrics

The user-facing verdict rests on three numbers under load:

- **Memory per connection** — measured *server-side* by [`scripts/sample-memory.sh`](scripts/sample-memory.sh),
  which samples the target JVM's RSS (and best-effort `jcmd GC.heap_info` heap) at idle and under
  sustained load. k6 only sees the client; this is the only way to see what each connection costs the
  server. Reported as `(loaded RSS median − idle RSS median) / connections`.
- **Tail latency (p99)** — where thread-per-request pools fall apart: once concurrent requests exceed
  the ~200-thread pool, requests queue and p99 climbs steeply.
- **Error rate** — at thousands of connections, Tomcat's platform pool refuses/queues past its
  connection limit (~8192) and slow requests time out; virtual-thread targets shouldn't.

## Prerequisites

- **Java 25** (the repo toolchain) and **[k6](https://k6.io/docs/get-started/installation/)** on `PATH`.
- `jcmd`, `ps`, `curl`, `python3` (all standard with the JDK / OS).
- **Build the app jars first:**
  ```bash
  ./gradlew :netty-loom-spring-example-netty:bootJar :netty-loom-spring-example-tomcat:bootJar
  ```

### Raising OS limits for high connection counts

Both the k6 client and the server JVM need file descriptors ≥ connections + overhead:

```bash
ulimit -n 200000   # in the shell that launches run-all.sh
```

The harder limit at high VU counts on a **single machine** is **ephemeral-port exhaustion**: every
client connection consumes a source port toward the one server port.

- **macOS** (the default dev target): the ephemeral range is ~`49152–65535` (~16k ports;
  `sysctl net.inet.ip.portrange.first net.inet.ip.portrange.last`). After a high-VU run, that many
  client sockets sit in `TIME_WAIT` (~30s; `net.inet.tcp.msl`), so `run-all.sh` deliberately settles
  between targets (`SETTLE`). **~10k concurrent is reliable; 50k is not** — 10k held + 10k draining
  approaches the port ceiling, and the k6 client contends with the server for CPU on the same box.
- **Linux** (CI / a dedicated box): widen `net.ipv4.ip_local_port_range`, enable `net.ipv4.tcp_tw_reuse`,
  and for 50k add **loopback aliases** (`127.0.0.2`, `127.0.0.3`, …) so each client IP gets its own
  ~64k port space.

**For defensible 50k numbers, run the k6 client and the server on separate hosts** over a real NIC.
Single-box loopback 50k results are indicative at best — the README and snapshot label them as such.

## Running

The whole three-way sweep (build jars first):

```bash
cd netty-loom-spring-benchmarks
ulimit -n 200000
VUS=10000 DURATION=60s SETTLE=35 bash scripts/run-all.sh
```

This starts each target with identical JVM flags, waits for health, samples idle memory, runs all
three scenarios while sampling memory under load, tears the target down, drains, and repeats. It then
writes [`results/SNAPSHOT.md`](results/SNAPSHOT.md). Raw k6 exports and memory CSVs land in
`results/` and are git-ignored; only the curated snapshot is committed.

Knobs (env vars): `VUS` (connections for scenarios 2 and 3), `DURATION` (steady-state plateau),
`RAMP` (warmup ramp, trimmed when interpreting steady state), `SETTLE` (drain seconds, applied both
between targets and between scenarios 2 and 3), `JAVA_FLAGS` (applied **identically** to all three
for a fair memory comparison), `RESULTS_DIR` (where artifacts and the snapshot are written — point
it at a versioned subdirectory to keep a release's numbers).

`SETTLE` matters more now that each target runs two high-VU scenarios back to back: scenario 2 leaves
roughly `VUS` client sockets in TIME_WAIT just as scenario 3 asks for `VUS` more. Under-settling shows
up as connection failures in the secured run and reads like a Security problem.

### Run one target by hand

```bash
java -jar ../netty-loom-spring-example-netty/build/libs/*.jar                              # :18080
java -jar ../netty-loom-spring-example-tomcat/build/libs/*.jar --spring.profiles.active=platform  # :18081
java -jar ../netty-loom-spring-example-tomcat/build/libs/*.jar --spring.profiles.active=virtual   # :18082

k6 run --env BASE_URL=http://localhost:18080 --env VUS=10000 k6/high-concurrency.js
k6 run --env BASE_URL=http://localhost:18080 --env VUS=10000 k6/high-concurrency-secured.js
```

The secured scenario logs in as `bench`/`bench` (set in each example app's `application.properties`,
overridable with `--env USERNAME=... --env PASSWORD=...`).

## Interpreting results — and how to keep yourself honest

- **Tomcat's accept ceiling is config, not architecture — so we raise it.** Tomcat's NIO connector
  defaults to `max-connections=8192` and `threads.max=200`. Enabling virtual threads
  (`spring.threads.virtual.enabled=true`) replaces the worker pool with a `VirtualThreadExecutor` but
  leaves the acceptor, poller, and `max-connections` untouched (verified in spring-boot-tomcat 4.0.5's
  `TomcatVirtualThreadsWebServerFactoryCustomizer`). At 10k connections, the default would reset/queue
  ~1,800 of them at the accept ceiling, producing a tail-latency + error-rate collapse that's a config
  artifact, not an architectural one. `run-all.sh` therefore sets `max-connections = 2 × VUS` on both
  Tomcat targets, and raises `threads.max` to match on the **virtual** target (its executor isn't
  pool-bounded, but this forecloses the "you throttled the VT path" objection). The **platform** target
  keeps `threads.max=200` — the bounded thread-per-request pool *is* the architecture under test. The
  OS backlog (`accept-count=100`) is left at its default, ≈ Netty core's hardcoded `SO_BACKLOG=128`, and
  isn't the bottleneck under the 15s gradual ramp. Whatever gap survives this is architecture; the
  earlier default-config snapshot is in git history (commit `c4f4270`) for a before/after comparison.
- **Memory per connection is noise-dominated at low VU counts.** With `-Xmx2g` and no `-Xms`, G1 commits
  heap lazily, so RSS jumps ~100MB from GC/JIT regardless of connections. The metric only separates the
  targets once connections vastly outnumber the ~200-thread pool. The snapshot uses the steady-state
  **median** (not the transient peak) to suppress this.
- **The platform-thread target's footprint does not scale with offered load.** It caps at ~200 worker
  threads and refuses/queues the rest — so it can look memory-frugal while its p99 and error rate
  collapse. Read all three metrics together, not memory alone.
- **Coordinated omission.** The high-concurrency scenario is a *closed* model (`ramping-vus`): when the
  server saturates, slow responses throttle the offered load, so a dying server can look merely "slow"
  rather than overloaded. For the honest tail, also run an *open* model that holds offered RPS fixed
  (k6 `constant-arrival-rate`) and watch errors instead of latency.
- **Same box = contention — so we report CPU per target.** At 10k+ VUs the k6 client steals CPU from
  the server; raw throughput is partly client-bound. Part of Netty-Loom's edge is a leaner per-request
  pipeline, and on a contended box "cheaper per request" converts directly into "grabs more of the
  shared cores than k6 leaves for Tomcat." The **CPU efficiency** table in the snapshot is the cheap
  discriminator: it reports each server's average cores used (Δ cumulative CPU time / Δ wall over the
  load window) and **throughput per core**. Throughput-per-core is allocation-independent — if
  Netty-Loom serves more requests *per core* than Tomcat+VT, the win is structural and should survive
  going off-box; if it wins only by pinning more cores, that component is a contention artifact that
  off-box testing would compress (though the structural I/O-parallelism / poller advantage should
  persist). The two-host setup above remains the only fully defensible configuration; the CPU column
  tells you, on a single box, which story the numbers are telling.
- **Warmup/JIT and GC** are pinned the same way across all three (identical `JAVA_FLAGS`, a warmup ramp
  that's trimmed) so a difference can't be misattributed to the server model.

## Results snapshot

See [`results/SNAPSHOT.md`](results/SNAPSHOT.md) for the committed numbers, the exact machine, and the
JVM flags they were produced with. Regenerate any time by re-running `scripts/run-all.sh`.
