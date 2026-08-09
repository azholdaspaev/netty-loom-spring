# netty-loom-spring

Drop-in Spring Boot web server that replaces the embedded Tomcat/Jetty servlet container with a **Netty** HTTP server dispatching every request onto a **Java 25 virtual thread** (Project Loom). You keep writing ordinary blocking Spring MVC controllers — `@RestController`, `DispatcherServlet`, servlet semantics — but each request runs on a cheap virtual thread instead of a scarce platform thread, so blocking I/O no longer caps your concurrency. Targets only stable JDK features: **no `--enable-preview` for consumers**.

```kotlin
io.github.azholdaspaev:netty-loom-spring-boot-starter:0.1.0-SNAPSHOT
```

## Features

- Netty-based `ServletWebServerFactory` that transparently replaces Tomcat
- One **virtual thread per request** via `Executors.newVirtualThreadPerTaskExecutor()` — no pool sizing
- Standard Spring MVC programming model (`DispatcherServlet`, blocking controllers)
- Java NIO transport (`NioServerSocketChannel`) on every platform
- Two-phase **graceful shutdown** with configurable drain timeout
- **Slow-loris protection** via a per-channel read timeout that measures the client, not your handler
- HTTP frame-size limits to defeat parser abuse
- Pluggable pipeline and dispatch via two clean SPI seams

## Why

Thread-per-request is the most ergonomic server model: linear control flow, trivial debugging, normal stack traces. Its historical flaw is the **platform-thread ceiling** — each blocking call parks an OS thread, so a few hundred slow downstream calls exhaust the pool and throughput collapses. Virtual threads remove that ceiling: a parked virtual thread costs ~kilobytes, not megabytes, and unmounts its carrier while blocked. `netty-loom-spring` pairs Netty's event-loop acceptor (which never blocks) with a virtual thread per dispatched request, giving you thread-per-request ergonomics at event-loop-class concurrency.

## Quick start

### 1. Add the starter

**Gradle (Kotlin DSL)**

```kotlin
dependencies {
    implementation("io.github.azholdaspaev:netty-loom-spring-boot-starter:0.1.0-SNAPSHOT")
}
```

**Maven**

```xml
<dependency>
    <groupId>io.github.azholdaspaev</groupId>
    <artifactId>netty-loom-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

> The starter pulls in `spring-boot-starter-web` with its Tomcat excluded — the Netty `ServletWebServerFactory` takes over because it is the sole factory on the classpath. Don't add Tomcat back.

### 2. Write a normal Spring Boot app

```java
@SpringBootApplication
@RestController
public class DemoApplication {

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

### 3. Run it

```bash
./gradlew bootRun
# Listens on server.port (default 8080); override it in application.properties
```

```properties
server.port=8080
```

## Configuration

Standard knobs bind under Spring Boot's `server.*` namespace (`server.port`, `server.address`,
`server.servlet.context-path`, `server.ssl.*`); Netty-only tuning lives under the `server.netty`
prefix (`NettyLoomProperties`). See [ADR 0001](docs/adr/0001-server-properties-namespace.md).

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `server.port` | `int` | `8080` | Listening port; `0` lets the OS select an available port |
| `server.address` | `InetAddress` | all interfaces | Network address to bind; unset binds every interface |
| `server.servlet.context-path` | `String` | `""` | Context path the application is mounted under |
| `server.servlet.session.timeout` | `Duration` | `30m` | Session idle timeout; honoured at second resolution, and `0` (or less) means sessions never expire |
| `server.servlet.session.cookie.*` | — | `JSESSIONID`, `HttpOnly` | Session cookie name, path, domain, `http-only`, `secure`, `max-age`, `same-site` |
| `server.servlet.session.tracking-modes` | `Set` | `cookie` | Only `cookie` is supported; anything else fails startup rather than being silently ignored |
| `server.netty.boss-threads` | `int` | `1` | Netty boss group thread count (accepts connections) |
| `server.netty.worker-threads` | `int` | `0` | Netty worker group thread count; `0` = Netty default (`CPU_COUNT * 2`) |
| `server.netty.tcp-keep-alive` | `boolean` | `true` | TCP `SO_KEEPALIVE` socket option; unrelated to HTTP keep-alive, which is protocol behaviour and always on |
| `server.netty.shutdown-grace-period` | `Duration` | `30s` | Time to wait for in-flight requests before forcibly closing |
| `server.netty.read-timeout` | `Duration` | `30s` | How long the server waits on the client. A **single** interval measured from the previous response — or from the connection being accepted, which is what makes it a slow-loris defense — covering idle time and delivery of the next request together, not one interval each. Handler execution time does **not** count against it. Channels exceeding it are closed without a response; `0` or negative disables |

Fixed HTTP frame limits (not yet configurable): max initial line, header, and chunk size = **10 KB** each; max aggregated body = **1 MB**. An over-limit initial line (`414`) or header block (`431`) is answered and the connection closed, rather than dispatched to your application; an over-limit body is answered `413` by the aggregator, which leaves a keep-alive connection open.

### Response framing

Framing follows Tomcat, which means most handlers are chunked rather than length-declared, and that is
worth knowing before you measure it:

| Handler returns | Framing | Why |
|---|---|---|
| `ResponseEntity<Pojo>` | `Transfer-Encoding: chunked` | Spring flushes the response after writing an entity, and Jackson cannot report a length without serialising first |
| `ResponseEntity<String>` | `Content-Length` | same flush, but `StringHttpMessageConverter` does report a length |
| `@ResponseBody` (any type) | `Content-Length` | never flushed, so it commits once, whole |
| body over 8 KB, or an explicit `flushBuffer()` | `Transfer-Encoding: chunked` | committed before the body is complete |
| `204`, `205`, `304`, `1xx` | `Content-Length: 0` | no body can follow, so nothing is framed for one |

The `ResponseEntity` rows are Spring's doing, not this server's: `HttpEntityMethodProcessor` calls
`flush()` after writing an entity, and a flush commits. Tomcat behaves identically — verified by
running the same endpoints on `netty-loom-spring-example-tomcat`.

## Architecture

Three library modules, dependency flow **`starter → mvc → core`** (core has no Spring dependency):

| Module | Responsibility |
| --- | --- |
| `netty-loom-spring-core` | Pure-Netty HTTP foundation: `NettyServer` lifecycle, `NettyServerChannelInitializer`, pipeline SPI, dispatch SPI, virtual-thread `HttpRequestHandler`, `HttpExceptionHandler`, graceful-shutdown semantics. No Spring. |
| `netty-loom-spring-mvc` | Servlet bridge: `SpringHttpRequestDispatcher` wraps `DispatcherServlet`, adapting `FullHttpRequest`/`FullHttpResponse` to `NettyHttpServletRequest`/`NettyHttpServletResponse` over `DefaultNettyServletContext`. |
| `netty-loom-spring-boot-starter` | `NettyLoomAutoConfiguration` (runs `@AutoConfiguration(before = WebMvcAutoConfiguration.class)`), `NettyWebServerFactory` (`ServletWebServerFactory`), `NettyWebServer` (`WebServer`), `NettyLoomProperties`. |

### SPI seams

- **`NettyPipelineConfigurer`** — `void configure(ChannelPipeline)`. The seam for customizing the Netty channel pipeline. `DefaultNettyPipelineConfigurer` builds it from a `List<NamedChannelHandler>`; `NamedChannelHandler.shared(name, handler)` enforces the `@Sharable` annotation at construction to prevent cross-channel reuse bugs.
- **`HttpRequestDispatcher`** — `FullHttpResponse handle(FullHttpRequest)`. The seam between the Netty pipeline and any higher-layer router. Keeps `core` free of Spring; `SpringHttpRequestDispatcher` is the MVC implementation.

### Request flow

```
TCP accept (Netty boss loop)
  → worker loop pipeline:
      HttpServerCodec                # decodes the request, encodes the response
      → HttpServerKeepAliveHandler   # honours Connection: close
      → HttpDrainHandler             # counts the exchange so graceful shutdown can wait for it
      → HttpObjectAggregator(1MB)    # buffers full request body
      → HttpReadTimeoutHandler(30s)  # client-progress deadline; suspended while dispatching
      → HttpPipeliningHandler        # one exchange at a time, so responses leave in request order
      → HttpDecoderFailureHandler    # rejects what the codec could not parse, before it reaches the app
      → HttpRequestHandler           # retains request, hands off to...
          → virtual thread (Executors.newVirtualThreadPerTaskExecutor)
              → SpringHttpRequestDispatcher
                  → NettyHttpServletRequest / NettyHttpServletResponse
                      → DispatcherServlet.service()   # your @Controller, blocking is fine
          ← FullHttpResponse written + flushed; request buffer released
      → HttpExceptionHandler (@Sharable)  # maps errors to status, closes client disconnects
```

The event loop only accepts and decodes; all blocking application work happens on the per-request virtual thread, leaving the loop free to keep accepting connections.

`HttpReadTimeoutHandler` sits below the aggregator deliberately. It counts requests, not bytes, so the interval is a deadline for the client to deliver a whole request — a client dribbling a header a byte at a time is closed, where a byte-level clock would be refreshed by every byte and hold the connection forever. It also means a request being dispatched suspends the clock, so however long your controller blocks, the connection is never closed out from under it.

The clock arms when the connection is accepted — a client that connects and says nothing is closed one interval later, which is the slow-loris defense — and restarts each time a response is written.

The cost of counting requests rather than bytes is that idle time and delivery share one budget. Nothing inbound advances the clock, so a pooled connection that sits idle for most of the interval has only the remainder left to deliver its next request:

| Scenario (30s timeout) | Outcome |
| --- | --- |
| Slow handler, any duration | answered — dispatch is exempt |
| Idle 5s, then a request arrives | fine — 25s left to deliver it |
| **Idle 29s, then a large upload begins** | **closed 1s in, mid-upload** |
| Header dribbled a byte at a time | closed — the loris defense |

The last two are the same mechanism. If your clients pool connections and send large bodies after long idle periods, size `read-timeout` for the sum, not for either part.

`HttpExceptionHandler` maps: `ReadTimeoutException` → close; `ClosedChannelException`/`PrematureChannelClosureException`/client-disconnect `IOException` → clean close; `TooLongHttpHeaderException` → 431; `TooLongHttpLineException` → 414; `TooLongFrameException` → 413; `DecoderException`/`IllegalArgumentException` → 400; `UnsupportedOperationException` → 501; else → 500 (5xx logged as error, <5xx as warn).

## Benchmarks

**Methodology.** Three identical blocking Spring MVC apps — Netty-Loom (`:18080`), Tomcat platform threads (`:18081`, `threads.max=200`), Tomcat virtual threads (`:18082`, `threads.max=20000`) — driven by k6 against `GET /work` (`Thread.sleep(50)`, simulating a blocking DB call) at **10,000 concurrent connections**, 60s plateau. Single box, Darwin 25.5.0 ARM64, 8 logical cores, Java 25, identical JVM flags (`-XX:+UseG1GC -Xmx2g`). Tomcat `max-connections=20000` on both targets to remove the accept ceiling as a confound.

**Results @ 10,000 concurrent blocking connections**

| Metric | Netty-Loom | Tomcat-virtual | Tomcat-platform |
| --- | --- | --- | --- |
| Throughput (req/s) | **41,412** | 12,640 (3.3×) | 3,834 (10.8×) |
| p95 latency | **392 ms** | 3,233 ms | 2,643 ms |
| p99 latency | **447 ms** | 5,417 ms (12.1×) | 2,653 ms (5.9×) |
| Per-core throughput (req/s/core) | **22,908** | 5,734 (4.0×) | 11,799 (1.94×) |
| CPU used (of 8 cores) | 1.81 (22.6%) | 2.20 (27.6%) | 0.32 (4.1%, pool-capped) |
| Memory / connection | **20.17 KB** | 148.50 KB (7.4×) | 29.29 KB |
| Error rate | 0.00% | 0.00% | 0.00% |

At **low concurrency** (1–10 VUs, `GET /ping`) all three are essentially equivalent (~25.8k–26.0k req/s) — the gain is structural and shows up only under blocking concurrency, not as raw transport overhead.

> Single-box loopback test: client and server share the 8 cores, so absolute latencies are inflated and throughput is relative. **Per-core throughput** is the discriminator most likely to transfer off-box. Tomcat-platform's low memory is an artifact of its ~200-thread cap queuing/refusing excess load — read throughput, tail latency, and CPU efficiency together. Numbers are from 2026-06-13 on darwin-arm64; relative ordering transfers more reliably than absolute values. See [`netty-loom-spring-benchmarks`](netty-loom-spring-benchmarks) for the full harness and caveats.

**Reproduce**

```bash
# Build the benchmark jars
./gradlew :netty-loom-spring-example-netty:bootJar :netty-loom-spring-example-tomcat:bootJar

# Raise OS file-descriptor limit
ulimit -n 200000

# Full sweep → writes results/SNAPSHOT.md
cd netty-loom-spring-benchmarks && VUS=10000 DURATION=60s SETTLE=35 bash scripts/run-all.sh
```

Run targets by hand:

```bash
java -jar netty-loom-spring-example-netty/build/libs/*.jar                                      # Netty       :18080
java -jar netty-loom-spring-example-tomcat/build/libs/*.jar --spring.profiles.active=platform   # Tomcat-plat :18081
java -jar netty-loom-spring-example-tomcat/build/libs/*.jar --spring.profiles.active=virtual    # Tomcat-virt :18082
```

```bash
# High-concurrency scenario against one target
k6 run --env BASE_URL=http://localhost:18080 --env VUS=10000 --env DURATION=60s --env RAMP=15s \
  --quiet --summary-export results/custom_high.json netty-loom-spring-benchmarks/k6/high-concurrency.js
```

## Requirements

- **Java 25** (LTS) toolchain — no `--enable-preview` for consumers
- **Spring Boot 4.0.x** (built against BOM 4.0.5)
- **Netty 4.2.x** (built against 4.2.12.Final)

## Build & test

```bash
./gradlew build                              # Full build (compile + test)
./gradlew build -x test                      # Build without tests
./gradlew :netty-loom-spring-core:test       # Test a single module
./gradlew test --tests 'io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.test.SmokeControllerTest'  # Single test
```

### CI

Every push to `main` and every pull request runs `./gradlew build` on Linux and macOS (`.github/workflows/build.yml`); both matrix cells must pass to merge.

Labelling a pull request `review/claude` additionally runs an automated two-pass Claude review that leaves inline comments. It is advisory only — never a required check, and it does not run unless a maintainer applies the label.

## Limitations & status

**Early / `0.1.0-SNAPSHOT`** — API may change, and the artifact is not yet published to Maven Central. The servlet bridge is deliberately minimal:

- **Requests are buffered whole** — a request body is accumulated in memory before the handler sees it (issue #51); fine for virtual threads, unsuitable for very large uploads. Responses stream: see below.
- **Responses stream** — writing to `HttpServletResponse.getOutputStream()` flushes incrementally as `Transfer-Encoding: chunked` once the body outgrows the response buffer (8 KB, `setBufferSize`) or the handler calls `flushBuffer()`, and a handler producing faster than the client reads is made to wait. A handler that never flushes and stays inside the buffer is sent as a single `Content-Length` response — but note Spring flushes for you on every `ResponseEntity`, so most handlers are chunked; see [response framing](#response-framing) above.
- **Synchronous only** — `startAsync()` returns `null`, `isAsyncSupported()` is `false`, `setReadListener` throws; servlet async is not supported. `SseEmitter`, `DeferredResult` and `StreamingResponseBody` all route through `startAsync()`, so they remain unavailable even though the response path beneath them now streams (issue #18).
- **Sessions are in-memory and cookie-tracked** — no URL rewriting (`encodeURL` is the identity), no persistence across restarts (`server.servlet.session.persistent=true` fails startup rather than silently losing sessions), and no distributed store; use Spring Session for that.
- **Session attributes never see activation events** — a bound value implementing `HttpSessionActivationListener` is never notified, because sessions are never passivated or activated. `HttpSessionBindingListener` is fired on both the bind and unbind paths, so the gap is only the activation half, and it is silent: an application relying on `sessionDidActivate` to re-establish transient state gets no signal here.
- **Servlet listeners: all seven `addListener` types are fired** — `ServletContextListener`, `ServletContextAttributeListener`, `ServletRequestListener`, `ServletRequestAttributeListener`, `HttpSessionListener`, `HttpSessionAttributeListener` and `HttpSessionIdListener`, registered via `ServletContext.addListener` (so `@WebListener`, `ServletListenerRegistrationBean` and `HttpSessionEventPublisher` all work). Those seven are the complete set the servlet spec defines for `addListener`; anything else is rejected with `IllegalArgumentException`, as it is on Tomcat. `HttpSessionActivationListener` is registered by being bound as a session attribute and `AsyncListener` through `AsyncContext.addListener`, so neither belongs here in any container.
- **No forwarded-header support, so the session cookie is not `Secure` behind a TLS-terminating proxy** — `Secure` is derived from the actual connection, and `server.forward-headers-strategy` is not honoured (issue #84). If you terminate TLS at nginx/ALB, set `server.servlet.session.cookie.secure=true` explicitly, or the session id can leak over a forced plaintext request (CWE-614).
- **No auth** — `getUserPrincipal()` → `null`, `isUserInRole()` → `false`; `authenticate`/`login`/`logout` are no-ops.
- **No resource serving / JSP** — `getResource*`/`getRealPath` return `null`; resource paths and request dispatch throw `UnsupportedOperationException`. Intended for application logic only.
- **Network metadata stubbed** — `getRemoteAddr/Host/Port`, `getScheme`, `getServerName/Port` return empty/0; designed for proxied deployments.
- **No write-timeout handler, and no bound on queued responses** — only `HttpReadTimeoutHandler` is configured, and it measures the client, not the socket. A peer that stops reading is reclaimed only *between* exchanges, and only while `read-timeout` is positive: the clock is suspended for as long as requests are outstanding, so a client that pipelines steadily while never reading holds the connection open indefinitely and the responses accumulate in Netty's outbound buffer with no ceiling. Because the server generates far more than the client sends, this amplifies — one connection, ~900 bytes in, has been measured queuing ~5.7 MB out (issue #88). Setting `read-timeout` to `0` or a negative value removes the reclamation entirely, leaving only shutdown.
- **No request/dispatch deadline** — the read timeout deliberately does not bound handler execution, so a handler that never returns holds its connection indefinitely. Nothing reclaims it short of shutdown.
- HTTP frame-size limits are fixed (not yet configurable).

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
