# Architecture Plan: Netty Loom Spring Boot Starter

**Created:** 2026-01-08
**PRD Reference:** ./PRD.md
**Research Reference:** ./RESEARCH.md

## 1. Executive Summary

This architecture defines a production-ready Spring Boot Starter that replaces Tomcat with Netty for Spring MVC applications while leveraging Java 21+ virtual threads for blocking operations. The design follows the **Servlet-Based Integration** approach, implementing Spring Boot's `ServletWebServerFactory` interface with a Netty backend.

**Core Design Principles:**
- Drop-in replacement for Tomcat (no application code changes required)
- Virtual threads handle all blocking Spring MVC processing
- Netty event loops remain platform threads for efficient NIO
- Minimal Servlet API implementation (only Spring MVC-required subset)
- Full Spring Boot auto-configuration integration

## 2. System Context

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Spring Boot Application                          │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                   │
│  │ @Controller  │  │   Filters    │  │ Interceptors │                   │
│  └──────────────┘  └──────────────┘  └──────────────┘                   │
│           │                │                │                            │
│           ▼                ▼                ▼                            │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                     Spring MVC (DispatcherServlet)                │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                    │                                     │
│                                    ▼                                     │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │           netty-loom-spring-mvc (Servlet Adapters)                │   │
│  │  ┌──────────────────────┐  ┌────────────────────────────────┐    │   │
│  │  │ NettyHttpServletReq  │  │ NettyHttpServletResponse      │    │   │
│  │  │ NettyServletContext  │  │ FilterChainAdapter             │    │   │
│  │  └──────────────────────┘  └────────────────────────────────┘    │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                    │                                     │
│                                    ▼                                     │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │              netty-loom-spring-core (Netty Server)                │   │
│  │  ┌─────────────┐  ┌─────────────────────┐  ┌──────────────────┐  │   │
│  │  │ NettyServer │  │ SpringMvcBridgeHdlr │  │ VirtualThreadExec│  │   │
│  │  └─────────────┘  └─────────────────────┘  └──────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                    │                                     │
│                                    ▼                                     │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │           netty-loom-spring-boot-starter (Auto-Config)            │   │
│  │  ┌────────────────────────────┐  ┌───────────────────────────┐   │   │
│  │  │ NettyServerAutoConfig      │  │ NettyServletWebServerFac  │   │   │
│  │  └────────────────────────────┘  └───────────────────────────┘   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
                     ┌─────────────────────────┐
                     │    Network (HTTP/1.1)    │
                     └─────────────────────────┘
```

### Integration Points

| Integration Point | Interface | Description |
|-------------------|-----------|-------------|
| Spring Boot Server | `ServletWebServerFactory` | Factory that creates Netty-based WebServer |
| Spring MVC | `DispatcherServlet.service()` | Entry point for request processing |
| Servlet API | `HttpServletRequest/Response` | Adapters bridge Netty to Servlet |
| Actuator | `HealthIndicator`, `MeterBinder` | Health checks and metrics |
| Configuration | `@ConfigurationProperties` | Property binding |

## 3. Module Structure

### Module: netty-loom-spring-core

**Responsibility:** Core Netty HTTP server implementation with virtual thread executor.

**Package Structure:**
```
io.github.azholdaspaev.nettyloom.core/
├── server/
│   ├── NettyServer.java              # Main server bootstrap
│   ├── NettyWebServer.java           # WebServer interface impl
│   └── NettyServerConfiguration.java # Server config POJO
├── handler/
│   ├── SpringMvcBridgeHandler.java   # Main channel handler
│   └── HttpRequestHandler.java       # Request processing
├── pipeline/
│   ├── NettyPipelineConfigurer.java  # Channel pipeline setup
│   └── HttpServerInitializer.java    # Channel initializer
├── ssl/
│   └── SslContextFactory.java        # SSL/TLS configuration
└── executor/
    └── VirtualThreadExecutorFactory.java # VT executor creation
```

**Dependencies:**
- `io.netty:netty-all:4.1.100+`
- `org.springframework.boot:spring-boot:3.2+`

---

### Module: netty-loom-spring-mvc

**Responsibility:** Servlet API adapters and Spring MVC integration layer.

**Package Structure:**
```
io.github.azholdaspaev.nettyloom.mvc/
├── servlet/
│   ├── NettyHttpServletRequest.java     # HttpServletRequest impl
│   ├── NettyHttpServletResponse.java    # HttpServletResponse impl
│   ├── NettyServletContext.java         # ServletContext impl
│   ├── NettyServletInputStream.java     # Request body stream
│   └── NettyServletOutputStream.java    # Response body stream
├── filter/
│   ├── FilterChainAdapter.java          # Filter chain execution
│   └── FilterRegistrationAdapter.java   # Filter registration
└── request/
    ├── ParameterParser.java             # Query/form param parsing
    └── HeaderAdapter.java               # Header utilities
```

**Dependencies:**
- `project(":netty-loom-spring-core")`
- `jakarta.servlet:jakarta.servlet-api:6.0+`
- `org.springframework:spring-webmvc:6.1+`

---

### Module: netty-loom-spring-boot-starter

**Responsibility:** Spring Boot auto-configuration and starter packaging.

**Package Structure:**
```
io.github.azholdaspaev.nettyloom.autoconfigure/
├── NettyServerAutoConfiguration.java     # Main auto-config
├── NettyServletWebServerFactory.java     # ServletWebServerFactory impl
├── NettyServerProperties.java            # Configuration properties
├── NettyServerMetricsAutoConfiguration.java # Metrics auto-config
└── NettyServerHealthIndicator.java       # Health indicator
```

**Resources:**
```
META-INF/
├── spring/
│   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
└── additional-spring-configuration-metadata.json
```

**Dependencies:**
- `project(":netty-loom-spring-core")`
- `project(":netty-loom-spring-mvc")`
- `org.springframework.boot:spring-boot-autoconfigure:3.2+`
- `org.springframework.boot:spring-boot-starter-actuator` (optional)
- `io.micrometer:micrometer-core` (optional)

---

### Module: netty-loom-spring-example-netty

**Responsibility:** Example Spring Boot application using Netty-Loom server.

**Package Structure:**
```
io.github.azholdaspaev.nettyloom.example/
├── ExampleApplication.java              # Main application class
├── controller/
│   └── ExampleController.java           # REST endpoints
└── service/
    └── SimulatedService.java            # Blocking operations
```

**Dependencies:**
- `project(":netty-loom-spring-boot-starter")`
- `org.springframework.boot:spring-boot-starter-web` (excludes Tomcat)
- `org.springframework.boot:spring-boot-starter-actuator`

---

### Module: netty-loom-spring-example-tomcat

**Responsibility:** Identical example application using standard Tomcat (baseline).

**Package Structure:**
```
io.github.azholdaspaev.nettyloom.example/
├── ExampleApplication.java              # Main application class
├── controller/
│   └── ExampleController.java           # REST endpoints (identical)
└── service/
    └── SimulatedService.java            # Blocking operations (identical)
```

**Dependencies:**
- `org.springframework.boot:spring-boot-starter-web` (includes Tomcat)
- `org.springframework.boot:spring-boot-starter-actuator`

---

### Module: netty-loom-spring-benchmark

**Responsibility:** k6 load testing scripts for performance comparison.

**Structure:**
```
netty-loom-spring-benchmark/
├── scripts/
│   ├── cpu-bound.js                     # JSON serialization test
│   ├── io-bound.js                      # Simulated DB call test
│   ├── mixed-workload.js                # Combined scenarios
│   └── high-concurrency.js              # 10K+ connections test
├── run-benchmark.sh                     # Orchestration script
├── docker-compose.yml                   # For running apps
└── README.md                            # Benchmark instructions
```

## 4. Component Design

### NettyServer

**File:** `netty-loom-spring-core/.../server/NettyServer.java`

```java
public class NettyServer {
    private final NettyServerConfiguration config;
    private final ChannelHandler requestHandler;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public void start() throws InterruptedException;
    public void stop();
    public int getPort();
    public boolean isRunning();
    public void shutdownGracefully(Duration timeout, Runnable callback);
}
```

---

### NettyWebServer

**File:** `netty-loom-spring-core/.../server/NettyWebServer.java`

```java
public class NettyWebServer implements WebServer {
    private final NettyServer nettyServer;

    @Override public void start() throws WebServerException;
    @Override public void stop() throws WebServerException;
    @Override public int getPort();
    @Override public void shutDownGracefully(GracefulShutdownCallback callback);
}
```

---

### SpringMvcBridgeHandler

**File:** `netty-loom-spring-core/.../handler/SpringMvcBridgeHandler.java`

```java
@ChannelHandler.Sharable
public class SpringMvcBridgeHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final ServletContext servletContext;
    private final FilterChain filterChain;
    private final DispatcherServlet dispatcherServlet;
    private final ExecutorService virtualThreadExecutor;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        request.retain();
        virtualThreadExecutor.submit(() -> {
            try {
                NettyHttpServletRequest servletRequest = new NettyHttpServletRequest(request, servletContext);
                NettyHttpServletResponse servletResponse = new NettyHttpServletResponse();

                filterChain.doFilter(servletRequest, servletResponse);

                FullHttpResponse nettyResponse = servletResponse.toNettyResponse();
                ctx.writeAndFlush(nettyResponse);
            } finally {
                ReferenceCountUtil.release(request);
            }
        });
    }
}
```

---

### NettyServletWebServerFactory

**File:** `netty-loom-spring-boot-starter/.../NettyServletWebServerFactory.java`

```java
public class NettyServletWebServerFactory
    extends AbstractServletWebServerFactory {

    private final NettyServerProperties properties;

    @Override
    public WebServer getWebServer(ServletContextInitializer... initializers) {
        NettyServletContext servletContext = new NettyServletContext(getContextPath());

        for (ServletContextInitializer initializer : initializers) {
            initializer.onStartup(servletContext);
        }

        ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
        SpringMvcBridgeHandler bridgeHandler = new SpringMvcBridgeHandler(...);

        NettyServer nettyServer = new NettyServer(buildConfiguration(), bridgeHandler);
        return new NettyWebServer(nettyServer, properties.getShutdown().getTimeout());
    }
}
```

---

### NettyHttpServletRequest

**File:** `netty-loom-spring-mvc/.../servlet/NettyHttpServletRequest.java`

```java
public class NettyHttpServletRequest implements HttpServletRequest {
    private final FullHttpRequest nettyRequest;
    private final NettyServletContext servletContext;
    private final Map<String, Object> attributes;

    // Essential methods for Spring MVC
    public String getMethod();
    public String getRequestURI();
    public String getQueryString();
    public String getHeader(String name);
    public String getParameter(String name);
    public BufferedReader getReader();
    public ServletInputStream getInputStream();
    public Object getAttribute(String name);
    public void setAttribute(String name, Object o);
}
```

---

### NettyHttpServletResponse

**File:** `netty-loom-spring-mvc/.../servlet/NettyHttpServletResponse.java`

```java
public class NettyHttpServletResponse implements HttpServletResponse {
    private int status = 200;
    private final HttpHeaders headers;
    private final ByteArrayOutputStream content;

    public void setStatus(int sc);
    public void setHeader(String name, String value);
    public PrintWriter getWriter();
    public ServletOutputStream getOutputStream();

    public FullHttpResponse toNettyResponse() {
        ByteBuf buf = Unpooled.wrappedBuffer(content.toByteArray());
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, valueOf(status), buf);
        response.headers().add(headers);
        return response;
    }
}
```

## 5. Request Flow

```
1. HTTP Request Arrives
   └─▶ Netty NIO Event Loop (Platform Thread)
       └─▶ HttpServerCodec decodes HTTP/1.1
           └─▶ HttpObjectAggregator combines chunks
               └─▶ SpringMvcBridgeHandler.channelRead0()

2. Virtual Thread Dispatch
   └─▶ virtualThreadExecutor.submit(() -> {...})
       └─▶ New Virtual Thread spawned

3. Servlet Adapter Creation (Virtual Thread)
   └─▶ NettyHttpServletRequest wraps FullHttpRequest
   └─▶ NettyHttpServletResponse created (empty)

4. Filter Chain Execution
   └─▶ FilterChainAdapter.doFilter()
       └─▶ Filter 1, Filter 2, ... Filter N
       └─▶ DispatcherServlet.service()

5. Spring MVC Processing
   └─▶ HandlerMapping finds handler
       └─▶ @Controller method executes
           └─▶ Service layer (may block)
       └─▶ HandlerInterceptors execute

6. Response Commit
   └─▶ NettyHttpServletResponse.toNettyResponse()

7. Write to Channel
   └─▶ ctx.writeAndFlush(nettyResponse)
       └─▶ Back to Netty Event Loop
```

## 6. Configuration Model

```properties
# Standard Spring Boot properties (supported)
server.port=8080
server.address=0.0.0.0
server.servlet.context-path=/api
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=30s

# SSL Configuration
server.ssl.enabled=true
server.ssl.key-store=classpath:keystore.p12
server.ssl.key-store-password=secret

# Netty-specific properties
server.netty.boss-threads=1
server.netty.worker-threads=0
server.netty.max-content-length=10485760
server.netty.connection-timeout=30s
server.netty.idle-timeout=60s
server.netty.server-header=Netty-Loom
```

## 7. Implementation Phases

### Phase 1: Core Netty Server

**Goal:** Minimal HTTP server that can start, accept connections, and respond.

**Tasks:**
1. Create `NettyServer` with bootstrap configuration
2. Create `NettyServerConfiguration` POJO
3. Create `HttpServerInitializer` pipeline setup
4. Create basic `HttpRequestHandler` returning "Hello World"
5. Create `VirtualThreadExecutorFactory`

**Test:** `curl http://localhost:8080/any` returns "Hello World"

---

### Phase 2: Servlet Adapters

**Goal:** Complete servlet adapter implementations.

**Tasks:**
1. Implement `NettyHttpServletRequest`
2. Implement `NettyHttpServletResponse` with `toNettyResponse()`
3. Implement `NettyServletContext`
4. Implement `NettyServletInputStream` and `NettyServletOutputStream`
5. Implement `ParameterParser`

**Test:** Unit tests pass for all adapters.

---

### Phase 3: Spring Boot Integration

**Goal:** Auto-configuration that replaces Tomcat.

**Tasks:**
1. Create `NettyServletWebServerFactory`
2. Create `NettyWebServer`
3. Create `NettyServerAutoConfiguration`
4. Create `NettyServerProperties`
5. Create `SpringMvcBridgeHandler`
6. Configure auto-configuration imports

**Test:** Spring Boot app starts with `@RestController` working.

---

### Phase 4: MVC Compatibility

**Goal:** Full Spring MVC feature compatibility.

**Tasks:**
1. Implement `FilterChainAdapter`
2. Support all common annotations
3. Support `@ExceptionHandler` and `@ControllerAdvice`
4. Support `HandlerInterceptor`

**Test:** Filters, interceptors, exception handlers all work.

---

### Phase 5: Production Features

**Goal:** Production-ready operational features.

**Tasks:**
1. Implement graceful shutdown
2. Implement SSL/TLS support
3. Create `NettyServerHealthIndicator`
4. Create metrics with Micrometer
5. Add request logging

**Test:** HTTPS works, graceful shutdown completes, actuator shows health.

---

### Phase 6: Example Apps & k6 Benchmarks

**Goal:** Validate 50%+ performance improvement.

**Tasks:**
1. Create example-netty module
2. Create example-tomcat module (identical endpoints)
3. Create k6 load test scripts
4. Create benchmark orchestration script
5. Document results

**Test:** Benchmark shows 50%+ throughput improvement at 10K connections.

## 8. Critical Files

### Root Project
- `settings.gradle.kts` - Add new modules
- `build.gradle.kts` - Dependency versions

### netty-loom-spring-core
- `NettyServer.java`
- `NettyWebServer.java`
- `NettyServerConfiguration.java`
- `SpringMvcBridgeHandler.java`
- `HttpServerInitializer.java`
- `VirtualThreadExecutorFactory.java`
- `SslContextFactory.java`

### netty-loom-spring-mvc
- `NettyHttpServletRequest.java`
- `NettyHttpServletResponse.java`
- `NettyServletContext.java`
- `NettyServletInputStream.java`
- `NettyServletOutputStream.java`
- `FilterChainAdapter.java`
- `ParameterParser.java`

### netty-loom-spring-boot-starter
- `NettyServerAutoConfiguration.java`
- `NettyServletWebServerFactory.java`
- `NettyServerProperties.java`
- `NettyServerHealthIndicator.java`
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

### Example Apps
- `ExampleApplication.java`
- `ExampleController.java`
- `SimulatedService.java`

### Benchmark
- `cpu-bound.js`
- `io-bound.js`
- `mixed-workload.js`
- `high-concurrency.js`
- `run-benchmark.sh`

## 9. Test Strategy

| Module | Coverage | Focus |
|--------|----------|-------|
| core | 80%+ | Server lifecycle, handler invocation |
| mvc | 90%+ | Servlet adapter correctness |
| starter | 80%+ | Auto-configuration conditions |

### Integration Tests
- Server start/stop lifecycle
- SSL handshake
- Spring MVC controller invocation
- Filter chain execution

### Benchmark Tests
- Tomcat vs Netty-Loom comparison
- Multiple workload types
- Various concurrency levels

## 10. Dependencies

```kotlin
// Versions
nettyVersion = "4.1.104.Final"
springBootVersion = "3.2.1"
jakartaServletVersion = "6.0.0"

// Core
implementation("io.netty:netty-all:$nettyVersion")
implementation("org.springframework.boot:spring-boot:$springBootVersion")

// MVC
implementation("jakarta.servlet:jakarta.servlet-api:$jakartaServletVersion")
implementation("org.springframework:spring-webmvc:6.1.2")

// Starter
implementation("org.springframework.boot:spring-boot-autoconfigure:$springBootVersion")
compileOnly("org.springframework.boot:spring-boot-starter-actuator:$springBootVersion")
```
