# netty-loom-spring

A Spring Boot web server that replaces the embedded Tomcat servlet container with **Netty**,
dispatching every request onto a **Java 25 virtual thread**. You keep writing ordinary blocking
Spring MVC controllers — `@RestController`, `DispatcherServlet`, servlet semantics — but each
request runs on a virtual thread instead of a pooled platform thread, so blocking I/O no longer
caps concurrency. Targets only stable JDK features: no `--enable-preview` for consumers.

*Pre-release — not yet published to Maven Central. [What that means](#status).*

## Benchmarks

Three identical blocking Spring MVC apps driven by k6 against `GET /work` (a 50 ms `Thread.sleep`,
standing in for a blocking DB call), at 10,000 concurrent connections. The comparison is against
other **same-model** servers — Tomcat with virtual threads enabled, and Tomcat with a platform
thread pool — not against a reactive stack, which would be a rewrite rather than a swap.

| Metric | Netty-Loom | Tomcat + virtual threads | Tomcat + platform threads |
| --- | --- | --- | --- |
| Throughput per core (req/s/core) | **20,336** | 8,322 (2.4×) | 8,069 (2.5×) |
| Throughput (req/s) | **44,584** | 26,819 (1.7×) | 3,663 (12.2×) |
| p99 latency | **420 ms** | 2,409 ms (5.7×) | 2,831 ms (6.7×) |
| CPU used (of 8 cores) | 2.19 | 3.22 | 0.45 (pool-capped) |

**Where the advantage does not apply.** At 2,000 connections Netty-Loom and Tomcat + virtual threads
are statistically indistinguishable — 30,885 vs 30,128 req/s, inside the harness's ±11% noise floor,
with identical tails. At low concurrency (1–10 VUs, `GET /ping`) Netty-Loom is the **slowest** of
the three, by about 28 microseconds per request. There is no per-request speed advantage to claim;
the gain is structural and appears only as connection count climbs past what a thread-per-request
pool absorbs.

Read these as relative, not absolute: it is a single-box loopback test where client and server share
8 cores, so throughput-per-core is the one figure likely to transfer off-box. Memory per connection
is deliberately withheld — it rose 20.2 → 49.2 → 66.6 KB across three sweeps and is unattributed
([#144](https://github.com/azholdaspaev/netty-loom-spring/issues/144)).

Methodology, every sweep, and the reproduce recipe:
[`docs/benchmarks/`](docs/benchmarks) and [`netty-loom-spring-benchmarks/`](netty-loom-spring-benchmarks).

## Should you use this?

**Yes, if**

- You serve **thousands of concurrent connections** that block on downstream I/O — a database, an
  HTTP call, a queue. That is where the measured advantage lives.
- Your handlers are plain blocking Spring MVC: `@RestController`, `@RequestBody`, filters, sessions.
- You can work with a pre-release dependency that you build yourself.

**No, if**

- You need **TLS, HTTP/2 or response compression** terminated at the application. None are
  implemented; TLS fails startup, the other two are ignored.
- You use **SSE, `StreamingResponseBody`, `DeferredResult`, or file upload**. Servlet async and
  multipart are both absent.
- Your traffic is **below roughly 2,000 concurrent connections** — there is no measured gain, and at
  low concurrency this is the slowest of the three servers benchmarked.

Full detail: [what works, what doesn't](#what-works-what-doesnt) below, and the
[compatibility matrix](docs/compatibility-matrix.md).

## Status

> **Not released yet.** A `0.1.0-SNAPSHOT` is on
> [Central Snapshots](https://central.sonatype.com/repository/maven-snapshots/) and resolves once
> that repository is added; nothing is on Maven Central, so the release coordinates below resolve
> from nowhere.
>
> The servlet bridge is also deliberately partial — several standard Spring Boot settings are
> accepted and then silently ignored.

## Quick start

```bash
git clone https://github.com/azholdaspaev/netty-loom-spring
cd netty-loom-spring
./gradlew :netty-loom-spring-example-netty:bootRun    # listens on :18080
```

```bash
curl localhost:18080/ping        # pong
curl localhost:18080/work        # {"status":"ok","sleptMillis":50} after a 50ms blocking sleep
```

The example is an ordinary Spring Boot app: `@SpringBootApplication`, a `@RestController` with
blocking methods, and `implementation(project(":netty-loom-spring-boot-starter"))`. Nothing in the
controller knows it is running on Netty.

### Once published

The starter will pull in `spring-boot-starter-web` with Tomcat excluded; the Netty
`ServletWebServerFactory` takes over because it is the only factory on the classpath. Do not add
Tomcat back.

```kotlin
implementation("io.github.azholdaspaev:netty-loom-spring-boot-starter:0.1.0")
```

```xml
<dependency>
    <groupId>io.github.azholdaspaev</groupId>
    <artifactId>netty-loom-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

## What works, what doesn't

Ordinary blocking `@RestController` code works: request mapping, `@RequestBody` and
`@ResponseBody`, content negotiation, cookies, sessions, servlet filters registered as beans,
servlet listeners, and Spring Security — which works because it is a filter and keeps its own
context, not because the container's auth methods are implemented (they are not).

Three things do not. Method-by-method detail is in the
**[compatibility matrix](docs/compatibility-matrix.md)**.

### 1. Standard settings that are accepted and silently ignored

`NettyWebServerFactory` extends Boot's `AbstractConfigurableWebServerFactory`, so Spring Boot binds
and pushes the full `server.*` surface onto it. Accepting is not honouring, and these produce **no
warning, no startup failure and no signal of any kind** — the highest-risk category here. The four
that cost the most:

- `server.compression.*` — responses are never compressed ([#22](https://github.com/azholdaspaev/netty-loom-spring/issues/22))
- `server.http2.*` — HTTP/1.1 only ([#23](https://github.com/azholdaspaev/netty-loom-spring/issues/23))
- `server.shutdown=immediate` — the server drains anyway ([#87](https://github.com/azholdaspaev/netty-loom-spring/issues/87))

The [full list](docs/configuration.md#properties-that-are-silently-ignored) covers the rest,
including `server.server-header`, `server.mime-mappings.*` and `spring.mvc.servlet.path`.

### 2. Settings that fail startup loudly

These are the safe ones — you find out immediately, with a message naming the property and the
issue. The contrast with the list above is the point.

- `server.ssl.*` with SSL enabled — TLS is not implemented ([#16](https://github.com/azholdaspaev/netty-loom-spring/issues/16))
- `server.servlet.session.persistent=true` — sessions are in-memory only
- `server.servlet.session.tracking-modes` containing anything but `cookie`
- `server.netty.transport` set to an unknown value, or to a native transport unavailable on the platform

### 3. Unimplemented servlet features

- **No async ([#18](https://github.com/azholdaspaev/netty-loom-spring/issues/18)).** `startAsync()`
  returns `null` rather than throwing, so `SseEmitter`, `DeferredResult`, `StreamingResponseBody`
  and `Callable` return values fail — usually as an NPE at Spring's call site.
- **No multipart ([#14](https://github.com/azholdaspaev/netty-loom-spring/issues/14)).** `getParts()`
  is always empty; `@RequestParam MultipartFile` fails with a 400.
- **Extra servlets never run.** `addServlet(name, instance)` discards the instance and the chain
  terminates at `DispatcherServlet`, so a `ServletRegistrationBean` registers and is never invoked.
- **Filters registered by class never run.** `addFilter(name, Filter)` works; the `Class` and
  class-name overloads are dropped without a warning. URL-pattern mappings only.
- **No static resources or JSP ([#15](https://github.com/azholdaspaev/netty-loom-spring/issues/15)).**
  `getResource*` and `getRealPath` return `null`. `GET /` with an `index.html` present still
  returns 500 ([#59](https://github.com/azholdaspaev/netty-loom-spring/issues/59)): the forward to
  it now works and the resource is found, but `ServletContext.getMimeType` then throws.
- **`RequestDispatcher` does `forward` and the container's own error dispatch, nothing else
  ([#182](https://github.com/azholdaspaev/netty-loom-spring/issues/182)).** `include` and named
  dispatch throw; filters mapped to `INCLUDE` or `ASYNC` still never match. A forward
  leaves the response uncommitted where the spec would close it.
- **No TLS ([#16](https://github.com/azholdaspaev/netty-loom-spring/issues/16)), HTTP/2
  ([#23](https://github.com/azholdaspaev/netty-loom-spring/issues/23)), compression
  ([#22](https://github.com/azholdaspaev/netty-loom-spring/issues/22)) or WebSocket upgrade.**
- **The session cookie ignores forwarded headers ([#50](https://github.com/azholdaspaev/netty-loom-spring/issues/50)).**
  Its `Secure` flag comes from the actual connection, which behind a TLS-terminating proxy is
  plaintext — set `server.servlet.session.cookie.secure=true` explicitly, or the session id can leak
  over a forced plaintext request (CWE-614). `server.forward-headers-strategy=framework` does not
  cover this: it fixes the *request* via Boot's `ForwardedHeaderFilter`, not the cookie.
- **No container auth.** `getUserPrincipal()` → `null`, `isUserInRole()` → `false`; `login` and
  `logout` are silent no-ops that report no failure.
- **Requests are buffered whole**, capped at 1 MiB ([#51](https://github.com/azholdaspaev/netty-loom-spring/issues/51)). Responses do stream.
- **Filters and servlets are initialized but never destroyed** ([#103](https://github.com/azholdaspaev/netty-loom-spring/issues/103)).
- **No bound on queued responses** — a client that pipelines while never reading has been measured
  queuing ~5.7 MB out for ~900 bytes in
  ([#88](https://github.com/azholdaspaev/netty-loom-spring/issues/88)). The write-stall timeout
  (`server.netty.write-stall-timeout`, 60s by default) bounds this in time, not in space. Nothing
  bounds handler execution at all
  ([#43](https://github.com/azholdaspaev/netty-loom-spring/issues/43)).

## Configuration

Standard knobs bind under Spring Boot's `server.*` namespace; Netty-only tuning lives under
`server.netty.*`. The rule for which is which, and why, is [ADR 0001](docs/adr/0001-server-properties-namespace.md).

Seven Netty-only properties. Types, defaults and exact semantics are in
**[docs/configuration.md](docs/configuration.md#servernetty)**, which is where they are maintained:

| Property | Controls |
| --- | --- |
| `server.netty.transport` | Which transport to use, or `auto` to pick the best one available |
| `server.netty.boss-threads` | Size of the event-loop group that accepts connections |
| `server.netty.worker-threads` | Size of the event-loop group that reads and writes on them |
| `server.netty.tcp-keep-alive` | Socket-level `SO_KEEPALIVE` |
| `server.netty.shutdown-grace-period` | How long graceful shutdown drains before force-closing |
| `server.netty.read-timeout` | The slow-loris deadline, measured on the client rather than on your handler |
| `server.netty.write-stall-timeout` | How long a response waits on a client that has stopped reading it |

Honoured from the standard namespace: `server.port`, `server.address`,
`server.servlet.context-path`, `server.servlet.session.timeout`,
`server.servlet.session.cookie.*`, `server.servlet.session.tracking-modes`,
`server.servlet.context-parameters.*`, and `spring.servlet.encoding.*`. Everything else is in one of
the two lists above.

Two things to know before tuning any of it. `server.netty.shutdown-grace-period` must be set
strictly below `spring.lifecycle.timeout-per-shutdown-phase`, or a slow drain is not guaranteed to
finish before Spring tears the session store down
([#89](https://github.com/azholdaspaev/netty-loom-spring/issues/89) —
[why](docs/configuration.md#graceful-shutdown)). And the HTTP frame limits are fixed rather than
configurable ([#42](https://github.com/azholdaspaev/netty-loom-spring/issues/42) —
[the values, and the status each over-limit request gets](docs/configuration.md#fixed-limits)).

## Architecture

Thread-per-request is the most ergonomic server model — linear control flow, ordinary stack traces,
a debugger that works. Its historical flaw is the platform-thread ceiling: each blocking call parks
an OS thread, so a few hundred slow downstream calls exhaust the pool and throughput collapses.
Virtual threads remove that ceiling. `netty-loom-spring` pairs Netty's event-loop acceptor, which
never blocks, with one virtual thread per dispatched request.

Three library modules, dependency flow **`starter → mvc → core`**. `core` has no Spring dependency.

| Module | Responsibility |
| --- | --- |
| `netty-loom-spring-core` | Pure-Netty foundation: `NettyServer` lifecycle, transport selection, `HttpConnectionRegistry` (drain accounting), the virtual-thread `HttpRequestHandler`, and the SPI seams |
| `netty-loom-spring-mvc` | Servlet bridge: `SpringHttpRequestDispatcher` runs the filter chain and `DispatcherServlet` over `NettyHttpServletRequest` / `NettyHttpServletResponse` / `DefaultNettyServletContext` |
| `netty-loom-spring-boot-starter` | `NettyLoomAutoConfiguration`, `NettyWebServerFactory` (`ServletWebServerFactory`), `NettyWebServer`, `NettyLoomProperties` |

### Request flow

```
TCP accept (boss loop)
  → worker loop, in NettyServerChannelInitializer.initChannel:
      HttpConnectionRegistry.register(channel)   # before the pipeline is configured
  → then the pipeline, on that same loop:
      httpCodec          HttpServerCodec(10_000, 10_000, 10_000)
      httpKeepAlive      HttpServerKeepAliveHandler
      drain              HttpDrainHandler          # counts the exchange for graceful shutdown
      aggregator         HttpObjectAggregator(1 MiB)
      readTimeout        HttpReadTimeoutHandler    # client deadline; suspended while dispatching
      pipelining         HttpPipeliningHandler     # responses leave in request order
      decoderFailure     HttpDecoderFailureHandler # @Sharable
      dispatcher         HttpRequestHandler
          → virtual thread (Executors.newVirtualThreadPerTaskExecutor)
              → SpringHttpRequestDispatcher
                  → filter chain → DispatcherServlet.service()   # blocking is fine
          ← response parts streamed back through HttpResponseWriter
      exceptionHandler   HttpExceptionHandler      # @Sharable; maps errors to status
```

The event loop only accepts and decodes. All blocking application work happens on the per-request
virtual thread, so the loop stays free to keep accepting.

### SPI seams

- **`NettyPipelineConfigurer`** — `void configure(ChannelPipeline)`. Customizes the channel
  pipeline. The default implementation walks a `List<NamedChannelHandler>` that is assembled in the
  **starter**, not in core, so supplying your own replaces the entire list — frame limits and read
  timeout included.
- **`HttpRequestDispatcher`** — `void handle(FullHttpRequest, HttpConnectionMetadata, HttpResponseWriter) throws Exception`.
  The seam between the Netty pipeline and any higher-layer router; keeps `core` free of Spring. A
  dispatcher that returns without having written a complete response is treated as a failure.
- **`HttpResponseWriter`** — `void write(HttpObject) throws IOException`. How a dispatcher emits a
  response, part by part. Not thread-safe, owns every part passed to it, and valid only for the
  duration of one `handle` call. The `IOException` is how a departed or stalled client reaches the
  dispatcher.

**Taking either of the first two seams needs `@Primary`.** Nothing in the starter is declared
`@ConditionalOnMissingBean`, so your bean does not displace the auto-configured one. Omit `@Primary`
and the context still starts while your bean is **never used** — Spring settles the ambiguity by
matching the injection point's parameter name against the auto-configuration's bean name, and that
one wins.

The pipeline handler names above (`httpCodec`, `drain`, `dispatcher`, …) are the addressable handles
for anyone reaching into the pipeline directly.

## Requirements

- **Java 25** (LTS) toolchain — no `--enable-preview` for consumers
- **Spring Boot 4.0.x** (built against BOM 4.0.5)
- **Netty 4.2.x** (built against 4.2.12.Final)

## Build

```bash
./gradlew build                              # compile + test
./gradlew :netty-loom-spring-core:test       # one module
```

Every push to `main` and every pull request runs `./gradlew build` on both Linux and macOS.

Contributions welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow, including the
test-first rule this repository enforces and what CI requires before a merge. Release history is in
[CHANGELOG.md](CHANGELOG.md).

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
