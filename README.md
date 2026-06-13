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
- **Slow-loris protection** via per-channel `ReadTimeoutHandler` (closes idle connections without a response)
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
# server.netty.port=0 by default → OS picks a free port; set it explicitly in application.properties
```

```properties
server.netty.port=8080
```

## Configuration

All properties live under the `server.netty` prefix (`NettyLoomProperties`).

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `server.netty.port` | `int` | `0` | Listening port; `0` lets the OS select an available port |
| `server.netty.boss-threads` | `int` | `1` | Netty boss group thread count (accepts connections) |
| `server.netty.worker-threads` | `int` | `0` | Netty worker group thread count; `0` = Netty default (`CPU_COUNT * 2`) |
| `server.netty.keep-alive` | `boolean` | `true` | TCP keep-alive on sockets |
| `server.netty.shutdown-grace-period` | `Duration` | `30s` | Time to wait for in-flight requests before forcibly closing |
| `server.netty.read-timeout` | `Duration` | `30s` | Per-channel idle timeout; channels exceeding it are closed without a response (slow-loris defense) |

Fixed HTTP frame limits (not yet configurable): max initial line, header, and chunk size = **10 KB** each; max aggregated body = **1 MB**.

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
      ReadTimeoutHandler(30s)        # idle-connection / slow-loris guard
      → HttpServerCodec
      → HttpObjectAggregator(1MB)    # buffers full request body
      → HttpRequestHandler           # retains request, hands off to...
          → virtual thread (Executors.newVirtualThreadPerTaskExecutor)
              → SpringHttpRequestDispatcher
                  → NettyHttpServletRequest / NettyHttpServletResponse
                      → DispatcherServlet.service()   # your @Controller, blocking is fine
          ← FullHttpResponse written + flushed; request buffer released
      → HttpExceptionHandler (@Sharable)  # maps errors to status, closes client disconnects
```

The event loop only accepts and decodes; all blocking application work happens on the per-request virtual thread, leaving the loop free to keep accepting connections.

`HttpExceptionHandler` maps: `ReadTimeoutException` → close; `ClosedChannelException`/client-disconnect `IOException` → clean close; `TooLongFrameException` → 413; `DecoderException`/`IllegalArgumentException` → 400; `UnsupportedOperationException` → 501; else → 500 (5xx logged as error, <5xx as warn).

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

## Limitations & status

**Early / `0.1.0-SNAPSHOT`** — API may change, and the artifact is not yet published to Maven Central. The servlet bridge is deliberately minimal:

- **Full buffering only** — request and response bodies are accumulated in memory (no streaming); fine for virtual threads, unsuitable for very large payloads.
- **Synchronous only** — `startAsync()` returns `null`, `isAsyncSupported()` is `false`, `setReadListener` throws; servlet async is not supported.
- **No sessions / auth** — `getSession()` → `null`, `getCookies()` → empty, `getUserPrincipal()` → `null`, `isUserInRole()` → `false`.
- **No resource serving / JSP** — `getResource*`/`getRealPath` return `null`; resource paths and request dispatch throw `UnsupportedOperationException`. Intended for application logic only.
- **Network metadata stubbed** — `getRemoteAddr/Host/Port`, `getScheme`, `getServerName/Port` return empty/0; designed for proxied deployments.
- **No write-timeout handler** — only `ReadTimeoutHandler` is configured; slow-write / stalled-response scenarios are not yet protected.
- HTTP frame-size limits are fixed (not yet configurable).

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
