# Netty-Loom-Spring

High-performance Spring Boot with Netty & Java 21 Virtual Threads

> **Development Stage**
>
> This library is currently in development (version 0.0.1-SNAPSHOT). API may change. Not recommended for production use yet.

## Introduction

Netty-Loom-Spring is a drop-in replacement for Tomcat in Spring Boot applications. It combines:

- **Netty** for high-performance, non-blocking I/O
- **Java 21 Virtual Threads** for efficient request handling
- **Spring MVC** compatibility with zero code changes

The virtual thread model allows thousands of concurrent connections with minimal memory overhead, eliminating traditional thread pool limits.

## Comparison with Other Spring Web Servers

| Aspect | Tomcat (Platform) | Tomcat + VT | WebFlux | Netty + VT + MVC |
|--------|-------------------|-------------|---------|------------------|
| Max concurrency | ~500 | Millions | Millions | Millions |
| I/O bound perf | Poor | Excellent | Excellent | Excellent+ |
| CPU bound perf | Good | Good | Good | Good |
| Code style | Blocking | Blocking | Reactive | Blocking |
| JDBC/JPA | Yes | Yes | No | Yes |
| Network layer | NIO (Tomcat) | NIO (Tomcat) | Netty | Netty |
| Request handler | Platform Thread | Virtual Thread | EventLoop | Virtual Thread |
| Context switches | High | Medium | Low | Low |
| Debugging | Easy | Easy | Hard | Easy |
| Migration effort | N/A | 1 line | Full rewrite | ~10 lines |
| Java version | Any | 21+ | Any | 21+ |

## Features

- Netty-based HTTP server with non-blocking I/O
- Java 21 Virtual Threads for each request
- Full Spring MVC compatibility (controllers, filters, interceptors)
- Spring Boot auto-configuration
- Configurable via `application.properties`
- Graceful shutdown support
- HTTP keep-alive handling

## Requirements

| Requirement | Version |
|-------------|---------|
| Java | 21+ |
| Spring Boot | 3.4.1+ |
| Netty | 4.1.116+ (transitive) |
| Jakarta Servlet | 6.0.0+ (transitive) |

## Quick Start

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.github.azholdaspaev.nettyloom:netty-loom-spring-boot-starter:0.0.1-SNAPSHOT")

    implementation("org.springframework.boot:spring-boot-starter-web") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.azholdaspaev.nettyloom</groupId>
    <artifactId>netty-loom-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <exclusions>
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-tomcat</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

### Example Application

```java
@SpringBootApplication
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}

@RestController
@RequestMapping("/api")
public class HelloController {

    @GetMapping("/hello")
    public String hello() {
        return "Hello from Netty!";
    }

    @GetMapping("/thread-info")
    public String threadInfo() {
        Thread current = Thread.currentThread();
        return "thread=" + current.getName() + ", virtual=" + current.isVirtual();
    }
}
```

## Configuration

Configure the Netty server via `application.properties` or `application.yml`:

```properties
server.port=8080
server.netty.boss-threads=1
server.netty.worker-threads=0
server.netty.max-content-length=10485760
server.netty.shutdown-timeout=30s
```

### Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `server.netty.boss-threads` | 1 | Number of threads accepting connections |
| `server.netty.worker-threads` | 0 | Number of I/O threads (0 = available processors) |
| `server.netty.max-content-length` | 10485760 | Maximum HTTP request body size in bytes (10 MB) |
| `server.netty.connection-timeout` | 30s | Timeout for new connections |
| `server.netty.idle-timeout` | 60s | Timeout for idle connections |
| `server.netty.shutdown-timeout` | 30s | Graceful shutdown wait time |
| `server.netty.server-header` | Netty-Loom | Custom Server response header |

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Spring Boot App                       │
├─────────────────────────────────────────────────────────┤
│  netty-loom-spring-boot-starter (Auto-configuration)    │
├─────────────────────────────────────────────────────────┤
│  netty-loom-spring-mvc (Servlet Adapters)               │
│  - NettyHttpServletRequest/Response                     │
│  - NettyServletContext                                  │
│  - FilterChainAdapter                                   │
├─────────────────────────────────────────────────────────┤
│  netty-loom-spring-core (Netty Server)                  │
│  - NettyServer                                          │
│  - VirtualThreadExecutorFactory                         │
└─────────────────────────────────────────────────────────┘
```

### Request Flow

1. HTTP request arrives at Netty event loop (non-blocking)
2. `SpringMvcBridgeHandler` dispatches to a virtual thread
3. Servlet adapters wrap Netty request/response objects
4. Filter chain executes, then `DispatcherServlet`
5. Response is converted back to Netty format and sent

## Migration Guide from Tomcat

### Step 1: Add the Starter Dependency

Add `netty-loom-spring-boot-starter` to your dependencies (see Quick Start).

### Step 2: Exclude Tomcat

Exclude Tomcat from `spring-boot-starter-web`:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-web") {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
}
```

### Step 3: Update Configuration

- `server.port` works the same way
- Add `server.netty.*` properties for tuning (optional)

### Step 4: Verify Startup

Look for this message in the logs:

```
Started NettyWebServer on port 8080
```

### What Works

- All Spring MVC annotations (`@RestController`, `@RequestMapping`, etc.)
- Servlet filters and interceptors
- Request/response body handling
- JSON serialization with Jackson
- Spring Security (filter-based)
- Spring Boot Actuator

### What Needs Adjustment

- Async servlets - not supported (virtual threads eliminate the need)
- Servlet 3.0+ async features
- WebSocket - use Netty's native WebSocket handlers instead

## Current Restrictions & Limitations

This section describes what is NOT supported in the current development version.

### Not Supported

| Feature | Notes |
|---------|-------|
| Async Servlets | `AsyncContext`, `startAsync()` - virtual threads eliminate the need |
| Servlet 3.0+ Async | `@WebServlet(asyncSupported=true)` has no effect |
| WebSocket | Servlet-based WebSocket not supported; use Netty's native handlers |
| HTTP/2 | Only HTTP/1.1 is currently supported |
| Server Push | Not available |
| Multipart File Upload | `Part` API not fully implemented |
| Session Clustering | No built-in distributed session support |
| SSL/TLS | Must be configured via reverse proxy (e.g., nginx) |

### Partial Support

| Feature | Notes |
|---------|-------|
| Servlet Context Attributes | Basic storage works; advanced lifecycle callbacks may be incomplete |
| Error Pages | Custom error page configuration not fully implemented |
| JSP/JSTL | Not tested; use Thymeleaf or other template engines |

### Known Issues

- Response buffering is in-memory only (large responses may consume significant memory)
- No streaming response support yet

## Module Structure

| Module | Description |
|--------|-------------|
| `netty-loom-spring-core` | Core Netty server with virtual thread executor |
| `netty-loom-spring-mvc` | Servlet API adapters for Spring MVC |
| `netty-loom-spring-boot-starter` | Spring Boot auto-configuration |

## Building from Source

```bash
git clone https://github.com/azholdaspaev/netty-loom-spring.git
cd netty-loom-spring
./gradlew build
```

### Running Tests

```bash
./gradlew test
```

## License

This project is open source. See the LICENSE file for details.
