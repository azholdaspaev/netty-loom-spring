# netty-loom-spring

A Spring Boot starter that replaces the embedded Tomcat container with **Netty** and runs every
request on a **Java 25 virtual thread**. Netty's event loop accepts and decodes; your blocking
`@RestController` code runs unchanged on a thread that parks for free. Stable JDK features only —
no `--enable-preview` for consumers.

The goal is the same blocking Spring MVC workload on **fewer cores**: at high connection counts the
per-request cost stays flat where Tomcat's climbs, and that difference is hardware you do not
have to buy. The goal is **not** a servlet container. The bridge implements the part of the
Servlet API a Spring MVC service exercises and does not aim at the full specification; the
method-by-method status is the [compatibility matrix](docs/compatibility-matrix.md).

## When to use it

**Yes, if**

- You serve **thousands of concurrent connections** that block on downstream I/O — a database, an
  HTTP call, a queue. That is where the measured advantage lives.
- Your handlers are plain blocking Spring MVC: `@RestController`, `@RequestBody`, filters, sessions,
  Spring Security.
- You run Spring Boot 4 on Java 25.

**No, if**

- You need **TLS, HTTP/2 or response compression** terminated at the application. None are
  implemented.
- You use **SSE, `StreamingResponseBody`, `DeferredResult` or file upload**. Servlet async and
  multipart are both absent.
- Your traffic is **below roughly 2,000 concurrent connections**. There is no measured gain below
  that, and no per-request speed advantage at any load.

## Benchmarks

Three targets running the same blocking controller — this starter, Tomcat with virtual threads
enabled, and Tomcat with a platform thread pool — driven by k6 against `GET /work` (a 50 ms
`Thread.sleep`, standing in for a blocking DB call) at 10,000 concurrent connections. The
comparison is against **same-model** servers, not a reactive stack, which would be a rewrite rather
than a swap. The figures are the [2026-08-23 sweep](docs/benchmarks/2026-08-23/COMPARISON.md): a
dedicated 4-core/8-thread Xeon, forward and reversed passes agreeing within 3% on 14 of 15 metrics.

| Metric | Netty-Loom | Tomcat + virtual threads | Tomcat + platform threads |
| --- | --- | --- | --- |
| Throughput per core (req/s/core) | **9,239** | 5,297 (1.74×) | 5,963 (1.55×) |
| Throughput (req/s) | **24,076** | 17,831 (1.35×) | 3,970 (6.07×) |
| p99 latency | **735 ms** | 2,427 ms (3.3×) | 2,542 ms (3.5×) |
| CPU used (of 8 threads) | 2.61 | 3.37 | 0.67 (pool-capped) |

**What the advantage is.** Netty-Loom's throughput per core is flat from 2,000 to 10,000
connections; Tomcat + virtual threads loses over a third of its. The two are equally efficient until
the connection count climbs past what a thread-per-request pool absorbs — then the same load costs
Netty-Loom 23% less CPU for 35% more throughput.

**Where the advantage does not apply.** At 2,000 connections Netty-Loom and Tomcat + virtual threads
are statistically indistinguishable — 23,400 vs 22,225 req/s, inside the harness's ±11% noise floor,
with the same tail. At low concurrency (1–10 VUs, `GET /ping`) Netty-Loom is a few microseconds
slower per request than Tomcat + virtual threads. There is no per-request speed advantage to claim.

Read these as relative, not absolute: it is a single-box loopback test where client and server share
the 8 threads, so throughput per core is the one figure likely to transfer off-box. Memory per
connection is deliberately withheld: the harness's figure measures the committed young generation,
not connection state ([2026-09-19](docs/benchmarks/2026-09-19/COMPARISON.md) §8).

Methodology, every sweep, and the reproduce recipe:
[`docs/benchmarks/`](docs/benchmarks) and [`netty-loom-spring-benchmarks/`](netty-loom-spring-benchmarks).

## Quick start

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

The starter brings `spring-boot-starter-web` with Tomcat excluded, and the Netty
`ServletWebServerFactory` takes over because it is the only one on the classpath. It serves only
when it is the sole factory: with Tomcat, Jetty or Undertow also present, or a
`ServletWebServerFactory` bean of your own, the auto-configuration yields and that server runs
instead.

Standard `server.*` settings apply; Netty-only tuning lives under `server.netty.*`. Every property,
its default, and every key that is accepted and ignored is in
[docs/configuration.md](docs/configuration.md).

To see it run without writing an app:

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
blocking methods, and the starter on the classpath. Nothing in the controller knows it is running
on Netty.

## Scope

Ordinary blocking `@RestController` code works: request mapping, `@RequestBody` and
`@ResponseBody`, content negotiation, cookies, sessions, servlet filters and listeners registered
as beans, and Spring Security — which works because it is a filter with its own
context, not because the container's auth methods are implemented.

What is not there, and is not planned as a servlet-fidelity goal:

- **No TLS** ([#16](https://github.com/azholdaspaev/netty-loom-spring/issues/16)), **HTTP/2**
  ([#23](https://github.com/azholdaspaev/netty-loom-spring/issues/23)), **compression**
  ([#22](https://github.com/azholdaspaev/netty-loom-spring/issues/22)) or WebSocket upgrade.
  TLS configured under `server.ssl.*` fails startup; the other two are ignored.
- **No servlet async** ([#18](https://github.com/azholdaspaev/netty-loom-spring/issues/18)), so
  `SseEmitter`, `DeferredResult`, `StreamingResponseBody` and `Callable` return values fail.
- **No multipart** ([#14](https://github.com/azholdaspaev/netty-loom-spring/issues/14)):
  `@RequestParam MultipartFile` fails with a 400.
- **Some standard `server.*` settings are accepted and silently ignored** — no warning, no startup
  failure. The list is in
  [docs/configuration.md](docs/configuration.md#properties-that-are-silently-ignored); the settings
  that fail loudly instead are beside it.

Everything else — request, response, dispatch, sessions, listeners, `ServletContext` — is recorded
method by method in the [compatibility matrix](docs/compatibility-matrix.md).

## Architecture

Thread-per-request is the most ergonomic server model — linear control flow, ordinary stack traces,
a debugger that works. Its historical flaw is the platform-thread ceiling: each blocking call parks
an OS thread, so a few hundred slow downstream calls exhaust the pool. Virtual threads remove that
ceiling. `netty-loom-spring` pairs Netty's event loop, which only accepts and decodes and never
blocks, with one virtual thread per dispatched request, and puts a servlet bridge — not a servlet
container — between the two so `DispatcherServlet` runs as it does anywhere else.

Three library modules, dependency flow **`starter → mvc → core`**. `core` has no Spring dependency.

| Module | Responsibility |
| --- | --- |
| `netty-loom-spring-core` | Pure Netty: `NettyServer` lifecycle, transport selection, `HttpConnectionRegistry` (drain accounting), the virtual-thread `HttpRequestHandler`, and the SPI seams |
| `netty-loom-spring-mvc` | Servlet bridge: `SpringHttpRequestDispatcher` runs the filter chain and `DispatcherServlet` over `NettyHttpServletRequest` / `NettyHttpServletResponse` / `DefaultNettyServletContext` |
| `netty-loom-spring-boot-starter` | `NettyLoomAutoConfiguration`, `NettyServletWebServerFactory` (`ServletWebServerFactory`), `NettyServletWebServer`, `NettyLoomProperties` |

### Request flow

```
TCP accept (boss loop)
  → worker loop, NettyServerChannelInitializer.initChannel:
      HttpConnectionRegistry.register(channel)   # before the pipeline is configured
  → the pipeline, on that same loop:
      httpCodec          HttpServerCodec
      httpKeepAlive      HttpServerKeepAliveHandler
      drain              HttpDrainHandler          # counts the exchange for graceful shutdown
      readTimeout        HttpReadTimeoutHandler    # client deadline; suspended while dispatching
      pipelining         HttpPipeliningHandler     # responses leave in request order
      decoderFailure     HttpDecoderFailureHandler
      bodyLimit          HttpRequestBodyLimitHandler
      dispatcher         HttpRequestHandler
          → virtual thread
              → SpringHttpRequestDispatcher → filter chain → DispatcherServlet.service()
          → request body read back off a bounded queue, AUTO_READ off
          ← response parts streamed back through HttpResponseWriter
      exceptionHandler   HttpExceptionHandler      # maps errors to status
```

The handler names are the addressable handles for anyone reaching into the pipeline directly.

### Extension points

`HttpRequestDispatcher` — `void handle(HttpRequest, InputStream, HttpConnectionMetadata, HttpResponseWriter) throws Exception` —
is the seam between the Netty pipeline and any higher-layer router, and what keeps `core` free of
Spring. `HttpResponseWriter` — `void write(HttpObject) throws IOException` — is how a dispatcher
emits a response part by part; the `IOException` is how a departed or stalled client reaches it.
`NettyPipelineDefinition`, the `List<NettyPipelineStep>` every connection's pipeline is built
from, is a bean assembled in the starter, so declaring your own replaces the entire list.

Every bean the starter declares is `@ConditionalOnMissingBean`, searched in the current context
only, so declaring your own replaces the auto-configured one without `@Primary`. The virtual-thread
dispatch executor is the exception, guarded by name: only a bean named `nettyLoomDispatchExecutor`
replaces it. A replacement declared in a child context must carry the default's bean name, or the
parent's default is the one injected.

## Requirements

- **Java 25** (LTS) toolchain — no `--enable-preview` for consumers
- **Spring Boot 4.0.x** (built against BOM 4.0.5)
- **Netty 4.2.x** (built against 4.2.12.Final)

## Build and contributing

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
