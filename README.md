# Netty Loom Spring

A drop-in replacement for Tomcat in Spring Boot applications that uses [Netty](https://netty.io/) as the HTTP server and [Java virtual threads](https://openjdk.org/jeps/444) (Project Loom) for request handling.

Write standard Spring MVC controllers — requests are dispatched on virtual threads automatically, giving you Netty's I/O performance with the simplicity of blocking code.

> **Status**: 0.1.0-SNAPSHOT — suitable for experimentation and development workloads.

## Requirements

- Java 24 with
- Gradle 8.14+

## Quick Start

Add the starter dependency to your Spring Boot application:

```kotlin
// build.gradle.kts
dependencies {
    implementation(project(":netty-loom-spring-boot-starter"))
}
```

That's it. The starter excludes Tomcat and auto-configures Netty with virtual threads. Your existing `@RestController` classes work unchanged:

```java
@SpringBootApplication
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

```java
@RestController
@RequestMapping("/api")
public class HelloController {

    @GetMapping("/hello")
    public Map<String, String> hello() {
        // Runs on a virtual thread — safe to block
        return Map.of("message", "Hello from Netty + Loom!");
    }
}
```

Configure the port in `application.yml`:

```yaml
server:
  port: 8081
```

## Modules

```
core  ←  mvc  ←  starter  ←  examples
```

| Module | Description |
|--------|-------------|
| `netty-loom-spring-core` | Netty server, pipeline handlers, HTTP abstractions. **No Spring or Servlet dependencies** (enforced by ArchUnit). |
| `netty-loom-spring-mvc` | Spring MVC integration — bridges Netty requests to `DispatcherServlet` via Jakarta Servlet wrappers. |
| `netty-loom-spring-boot-starter` | Spring Boot auto-configuration. Excludes Tomcat, wires everything together. |
| `netty-loom-spring-example-netty` | Example application running on Netty. |
| `netty-loom-spring-example-tomcat` | Same application running on Tomcat, for comparison. |

## Architecture

### Netty Pipeline

```
Client → HttpServerCodec → HttpObjectAggregator → IdleStateHandler
       → IdleConnectionCloser → HttpRequestDecoder → HttpResponseEncoder
       → RequestDispatcher (virtual thread) → Spring DispatcherServlet
```

Each incoming HTTP request is handed off to a virtual thread via `Executors.newVirtualThreadPerTaskExecutor()`. The Netty event loop is never blocked.

### Request Flow

1. Netty decodes the HTTP request and aggregates the body
2. `HttpRequestDecoder` converts Netty's `FullHttpRequest` to the internal `NettyHttpRequest`
3. `RequestDispatcher` submits the request to a virtual thread
4. The MVC module wraps it in `NettyHttpServletRequest`/`NettyHttpServletResponse` and forwards to Spring's `DispatcherServlet`
5. The response is converted back through `HttpResponseEncoder` to Netty's `FullHttpResponse`

### Configuration

`NettyServerConfig` (record with builder) controls server tuning:

| Property | Default | Description |
|----------|---------|-------------|
| `port` | 8080 | Listening port |
| `address` | loopback | Bind address |
| `bossThreads` | 1 | Netty boss group threads |
| `workerThreads` | CPU * 2 | Netty worker group threads |
| `maxInitialLineLength` | 4096 | Max HTTP request line bytes |
| `maxHeaderSize` | 8192 | Max total header bytes |
| `maxChunkSize` | 8192 | Max chunk size bytes |
| `maxContentLength` | 2 MB | Max request body bytes |
| `idleTimeout` | 60s | Connection idle timeout |
| `requestTimeout` | 60s | Per-request execution timeout |

Native transports (epoll on Linux, kqueue on macOS) are used automatically when available.

## Building

```bash
./gradlew build            # Full build + tests + formatting check
./gradlew test             # Tests only
./gradlew spotlessApply    # Auto-format code
./gradlew spotlessCheck    # Verify formatting
```

All tasks require `--enable-preview` (configured automatically by the build).

## Running the Examples

```bash
# Netty server on port 8081
./gradlew :netty-loom-spring-example-netty:run

# Tomcat server on port 8082 (for comparison)
./gradlew :netty-loom-spring-example-tomcat:run
```

## Benchmarks

The `netty-loom-spring-benchmarks/` directory contains [k6](https://k6.io/) load test scenarios:

- **Constant load** — sustained 130 virtual users
- **Ramp-up** — gradual traffic increase
- **Spike** — sudden traffic burst

```bash
cd netty-loom-spring-benchmarks
./run-benchmarks.sh
```

Results are written to `results/`.

## Tech Stack

| Component | Version |
|-----------|---------|
| Java | 24 (preview) |
| Netty | 4.1.130.Final |
| Spring Boot | 3.4.13 |
| Gradle | 8.14 (Kotlin DSL) |
| Formatter | Palantir Java Format 2.86.0 |

## Project Documentation

- [conventions.md](conventions.md) — coding conventions, naming patterns, testing rules
- [prod_readiness.md](prod_readiness.md) — production readiness assessment

## License

See [LICENSE](LICENSE) for details.
