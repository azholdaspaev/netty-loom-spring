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
- You rely on **Spring Boot's `/error` JSON body**. Error responses here have an empty body.
- Your traffic is **below roughly 2,000 concurrent connections** — there is no measured gain, and at
  low concurrency this is the slowest of the three servers benchmarked.

Full detail: [what works, what doesn't](#what-works-what-doesnt) below, and the
[compatibility matrix](docs/compatibility-matrix.md).

## Status

> **This library is not published anywhere.** There is no `maven-publish` configuration, no signing
> key and no release workflow in the repository, so the coordinates below do not resolve from any
> repository — not Maven Central, not a snapshot repo, not even your local Maven cache. Getting
> there is tracked by [#28](https://github.com/azholdaspaev/netty-loom-spring/issues/28) (namespace
> verification), [#29](https://github.com/azholdaspaev/netty-loom-spring/issues/29) (signing),
> [#30](https://github.com/azholdaspaev/netty-loom-spring/issues/30) (publishing config) and
> [#31](https://github.com/azholdaspaev/netty-loom-spring/issues/31) (release workflow).
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

Three things do not, in ascending order of how badly they will surprise you. Method-by-method detail
is in the **[compatibility matrix](docs/compatibility-matrix.md)**.

### 1. Standard settings that are accepted and silently ignored

`NettyWebServerFactory` extends Boot's `AbstractConfigurableWebServerFactory`, so Spring Boot binds
and pushes the full `server.*` surface onto it. Accepting is not honouring, and these produce **no
warning, no startup failure and no signal of any kind** — the highest-risk category here. The four
that cost the most:

- `server.compression.*` — responses are never compressed ([#22](https://github.com/azholdaspaev/netty-loom-spring/issues/22))
- `server.http2.*` — HTTP/1.1 only ([#23](https://github.com/azholdaspaev/netty-loom-spring/issues/23))
- `server.shutdown=immediate` — the server drains anyway ([#87](https://github.com/azholdaspaev/netty-loom-spring/issues/87))
- `spring.web.error.*` and error-page registrations — never read; there is no `ERROR` dispatch

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

- **No `/error` dispatch ([#38](https://github.com/azholdaspaev/netty-loom-spring/issues/38)).**
  `sendError` sets the status, discards the message and empties the body; `BasicErrorController` is
  never invoked. **Every** Spring-generated 400/404/405/415 reaches the client as a bare status line
  with a zero-length body, where Tomcat returns `{"timestamp":…,"status":…,"path":…}`. An uncaught
  controller exception yields `500` with a plain-text body and a closed connection. This is the
  first thing most people hit.
- **No async ([#18](https://github.com/azholdaspaev/netty-loom-spring/issues/18)).** `startAsync()`
  returns `null` rather than throwing, so `SseEmitter`, `DeferredResult`, `StreamingResponseBody`
  and `Callable` return values fail — usually as an NPE at Spring's call site.
- **No multipart ([#14](https://github.com/azholdaspaev/netty-loom-spring/issues/14)).** `getParts()`
  is always empty; `@RequestParam MultipartFile` fails with a 400 and an empty body.
- **Extra servlets never run.** `addServlet(name, instance)` discards the instance and the chain
  terminates at `DispatcherServlet`, so a `ServletRegistrationBean` registers and is never invoked.
- **Filters registered by class never run.** `addFilter(name, Filter)` works; the `Class` and
  class-name overloads are dropped without a warning. URL-pattern mappings only.
- **No static resources or JSP ([#15](https://github.com/azholdaspaev/netty-loom-spring/issues/15)).**
  `getResource*` and `getRealPath` return `null`; `request.getRequestDispatcher(path)` returns
  `null` where `ServletContext.getRequestDispatcher` throws. `GET /` with an `index.html` present
  returns 500 ([#59](https://github.com/azholdaspaev/netty-loom-spring/issues/59)).
- **No TLS ([#16](https://github.com/azholdaspaev/netty-loom-spring/issues/16)), HTTP/2
  ([#23](https://github.com/azholdaspaev/netty-loom-spring/issues/23)), compression
  ([#22](https://github.com/azholdaspaev/netty-loom-spring/issues/22)) or WebSocket upgrade.**
- **No forwarded-header support ([#50](https://github.com/azholdaspaev/netty-loom-spring/issues/50)).**
  The session cookie's `Secure` flag comes from the actual connection, which behind a
  TLS-terminating proxy is plaintext — set `server.servlet.session.cookie.secure=true` explicitly,
  or the session id can leak over a forced plaintext request (CWE-614).
- **No container auth.** `getUserPrincipal()` → `null`, `isUserInRole()` → `false`; `login` and
  `logout` are silent no-ops that report no failure.
- **Requests are buffered whole**, capped at 1 MiB ([#51](https://github.com/azholdaspaev/netty-loom-spring/issues/51)). Responses do stream.
- **Filters and servlets are initialized but never destroyed** ([#103](https://github.com/azholdaspaev/netty-loom-spring/issues/103)).
- **No write timeout and no bound on queued responses** — a client that pipelines while never
  reading has been measured queuing ~5.7 MB out for ~900 bytes in
  ([#88](https://github.com/azholdaspaev/netty-loom-spring/issues/88)). Nothing bounds handler
  execution either ([#43](https://github.com/azholdaspaev/netty-loom-spring/issues/43)).

## Configuration

Standard knobs bind under Spring Boot's `server.*` namespace; Netty-only tuning lives under
`server.netty.*`. The rule for which is which, and why, is [ADR 0001](docs/adr/0001-server-properties-namespace.md).

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `server.netty.transport` | `String` | `auto` | `auto` selects the best native transport (epoll on Linux, kqueue on macOS/BSD) and falls back to NIO. `nio` forces the portable transport. `epoll` and `kqueue` force that transport and fail startup if it is unavailable |
| `server.netty.boss-threads` | `int` | `1` | Threads in the boss event-loop group, which accepts connections |
| `server.netty.worker-threads` | `int` | `0` | Threads in the worker event-loop group; `0` uses Netty's default of `2 × availableProcessors()` |
| `server.netty.tcp-keep-alive` | `boolean` | `true` | Socket-level `SO_KEEPALIVE`. Unrelated to HTTP keep-alive, which is protocol behaviour and always on |
| `server.netty.shutdown-grace-period` | `Duration` | `30s` | How long graceful shutdown waits for in-flight requests before force-closing |
| `server.netty.read-timeout` | `Duration` | `30s` | A single client-progress deadline covering idle time and delivery of the next request *together*. Handler execution does not count against it. `0` or negative disables it |

Honoured from the standard namespace: `server.port`, `server.address`,
`server.servlet.context-path`, `server.servlet.session.timeout`,
`server.servlet.session.cookie.*`, `server.servlet.session.tracking-modes`,
`server.servlet.context-parameters.*`, and `spring.servlet.encoding.*`. Everything else is in one of
the two lists above. Details, and the reasoning behind the read-timeout design, are in
**[docs/configuration.md](docs/configuration.md)**.

**Set `server.netty.shutdown-grace-period` strictly below `spring.lifecycle.timeout-per-shutdown-phase`.**
Both default to 30s, so at the defaults a slow drain is not guaranteed to finish before Spring tears
the session store down ([#89](https://github.com/azholdaspaev/netty-loom-spring/issues/89)).

HTTP frame limits are fixed, not configurable
([#42](https://github.com/azholdaspaev/netty-loom-spring/issues/42)): max initial line, header block
and chunk are 10,000 bytes each, and the aggregated body is 1 MiB. An over-limit initial line is
answered `414` and a header block `431`, both closing the connection; an over-limit body is answered
`413`. The listen backlog is fixed at 128.

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
  → HttpConnectionRegistry.register(channel)     # before any handler exists
  → worker loop pipeline:
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
  **starter**, not in core, so replacing this bean replaces the entire list — frame limits and read
  timeout included.
- **`HttpRequestDispatcher`** — `void handle(FullHttpRequest, HttpConnectionMetadata, HttpResponseWriter) throws Exception`.
  The seam between the Netty pipeline and any higher-layer router; keeps `core` free of Spring. A
  dispatcher that returns without having written a complete response is treated as a failure.
- **`HttpResponseWriter`** — `void write(HttpObject)`. How a dispatcher emits a response, part by
  part. Not thread-safe, owns every part passed to it, and valid only for the duration of one
  `handle` call.

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

Every push to `main` and every pull request runs `./gradlew build` on both Linux and macOS, so that
epoll and kqueue are both exercised; both cells must pass to merge.

Contributions welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow, including the
test-first rule this repository enforces. Release history is in [CHANGELOG.md](CHANGELOG.md).

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
