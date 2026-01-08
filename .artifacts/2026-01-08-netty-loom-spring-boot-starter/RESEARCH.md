# Technical Research Report: Netty Loom Spring Boot Starter

**Created:** 2026-01-08
**PRD Reference:** ./PRD.md

## 1. Research Objectives

This research investigates the technical approaches for building a Spring Boot Starter that:
1. Replaces Tomcat with Netty as the embedded HTTP server
2. Leverages Java 21+ virtual threads (Project Loom) for blocking operations
3. Integrates with Spring MVC's DispatcherServlet for request routing
4. Achieves 50%+ performance improvement over Tomcat under high concurrency

## 2. Existing Codebase Analysis

### Current Project Structure

The project uses a multi-module Gradle build with Java 25:

```
netty-loom-spring/
├── build.gradle.kts           # Root build configuration
├── settings.gradle.kts        # Module definitions
├── netty-loom-spring-core/    # Core server components (empty)
│   └── src/main/java/.../NettyServer.java
├── netty-loom-spring-mvc/     # Spring MVC integration (empty)
└── .artifacts/                # AIDD workflow artifacts
```

**File References:**
- `/Users/azholdaspaev/Git/netty-loom-spring/build.gradle.kts` - Root build with Java 25 toolchain
- `/Users/azholdaspaev/Git/netty-loom-spring/netty-loom-spring-core/src/main/java/io/github/azholdaspaev/nettyloom/core/server/NettyServer.java` - Empty server class placeholder

### Missing Modules (Per PRD)

The PRD specifies four modules, but only two exist:
- `netty-loom-spring-boot-starter` - Auto-configuration (missing)
- `netty-loom-spring-benchmark` - Performance tests (missing)

### Integration Points

The new implementation must integrate with:
1. **Spring Boot's embedded server abstraction** - `WebServerFactory` hierarchy
2. **Spring MVC's request processing** - `DispatcherServlet.doDispatch()`
3. **Servlet API contracts** - `HttpServletRequest`/`HttpServletResponse`
4. **Spring Boot auto-configuration** - `@AutoConfiguration` classes

## 3. Technical Approaches

### Approach A: Servlet-Based Integration (Recommended)

**Description:** Implement a custom `ServletWebServerFactory` that creates a Netty-based `WebServer`, with servlet adapters bridging Netty's HTTP codec to the Servlet API. Virtual threads handle each request.

**Architecture:**
```
Netty EventLoop (NIO) → HttpServerCodec → NettyHttpServletRequest/Response
                                              ↓
                        Virtual Thread → DispatcherServlet.service()
                                              ↓
                              Spring MVC Handler Chain
```

**Key Interfaces to Implement:**
- `ServletWebServerFactory` - Factory for creating the server
- `WebServer` - Server lifecycle (start/stop/getPort)
- `HttpServletRequest` / `HttpServletResponse` - Servlet adapters
- `ServletContext` - Minimal implementation for Spring MVC

**Pros:**
- Full compatibility with existing Spring MVC controllers, filters, interceptors
- Leverages Spring Boot's existing auto-configuration infrastructure
- Drop-in replacement for Tomcat/Jetty/Undertow
- Can reuse Spring's MockHttpServletRequest/Response as implementation reference

**Cons:**
- Servlet API is large; implementing full spec is significant work
- Some servlet features may be impractical (sessions, JSP, multipart)
- Risk of subtle incompatibilities with servlet-dependent code

**Effort Estimate:** High (4-6 weeks for core, additional for edge cases)

**Reference Implementation:**
- [DanielThomas/spring-boot-starter-netty](https://github.com/DanielThomas/spring-boot-starter-netty) - Existing Netty servlet bridge for Spring Boot

---

### Approach B: HttpHandler-Based Integration (WebFlux Style)

**Description:** Use Spring WebFlux's `HttpHandler` abstraction as the bridge between Netty and Spring. Adapt incoming requests using `ServerHttpRequest`/`ServerHttpResponse` and route to Spring MVC via a custom adapter.

**Architecture:**
```
Netty EventLoop → ReactorHttpHandlerAdapter → HttpHandler
                                                  ↓
                    Virtual Thread → Custom MVC Adapter
                                                  ↓
                       HandlerMapping → HandlerAdapter
```

**Key Interfaces to Implement:**
- `ReactiveWebServerFactory` or custom `WebServerFactory`
- `HttpHandler` adapter for Spring MVC
- Custom request/response wrappers without full Servlet API

**Pros:**
- Cleaner, modern API without servlet baggage
- Better alignment with Netty's native abstractions
- Less code to implement than full servlet spec

**Cons:**
- Breaks compatibility with servlet filters and some Spring MVC features
- May require changes to existing Spring MVC applications
- Less mature integration path; less community support

**Effort Estimate:** Medium (3-4 weeks) but with compatibility tradeoffs

---

### Approach C: Helidon Nima Style (Custom Server)

**Description:** Build a purpose-built HTTP server from scratch using Netty's primitives, similar to Helidon Nima. Bypass Spring's server abstractions entirely and integrate at the handler level.

**Architecture:**
```
Custom NIO Server → Virtual Thread per Request → HandlerMapping
                                                      ↓
                               HandlerAdapter → Controller Methods
```

**Pros:**
- Maximum performance potential
- Clean architecture without legacy constraints
- Full control over threading model

**Cons:**
- Does not integrate with Spring Boot's embedded server infrastructure
- Cannot be a "drop-in replacement" - requires application changes
- Significant development effort
- Loses Spring Boot configuration integration

**Effort Estimate:** Very High (8+ weeks), with reduced Spring compatibility

**Reference:**
- [Helidon Nima](https://helidon.io/nima) - Virtual threads HTTP server by Oracle

## 4. Spring Boot Server Integration Deep Dive

### WebServerFactory Hierarchy

```
WebServerFactory (marker interface)
    ├── ServletWebServerFactory
    │       ├── AbstractServletWebServerFactory
    │       │       ├── TomcatServletWebServerFactory
    │       │       ├── JettyServletWebServerFactory
    │       │       └── UndertowServletWebServerFactory
    │       └── [NettyServletWebServerFactory - TO IMPLEMENT]
    │
    └── ReactiveWebServerFactory
            └── NettyReactiveWebServerFactory (existing)
```

### Required Interface: ServletWebServerFactory

```java
@FunctionalInterface
public interface ServletWebServerFactory extends WebServerFactory {
    WebServer getWebServer(ServletContextInitializer... initializers);
}
```

### Required Interface: WebServer

```java
public interface WebServer {
    void start() throws WebServerException;
    void stop() throws WebServerException;
    int getPort();
    default void shutDownGracefully(GracefulShutdownCallback callback) {
        callback.shutdownComplete(GracefulShutdownResult.IMMEDIATE);
    }
    default void destroy() { stop(); }
}
```

### Auto-Configuration Pattern

Spring Boot uses conditional auto-configuration to select the server:

```java
@AutoConfiguration
@ConditionalOnClass(Servlet.class)
@ConditionalOnWebApplication(type = Type.SERVLET)
public class ServletWebServerFactoryAutoConfiguration {
    // Imports specific server configurations
}

@Configuration
@ConditionalOnClass({ Tomcat.class, UpgradeProtocol.class })
static class EmbeddedTomcat {
    @Bean
    TomcatServletWebServerFactory tomcatServletWebServerFactory() { ... }
}
```

To integrate our Netty server:
1. Create `NettyServletWebServerFactory`
2. Create auto-configuration class with `@ConditionalOnClass(Netty.class)`
3. Ensure Tomcat is excluded when our starter is present

## 5. Netty HTTP Server Implementation

### Pipeline Architecture

```java
public class NettyHttpServerInitializer extends ChannelInitializer<SocketChannel> {
    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        
        // SSL (optional)
        if (sslContext != null) {
            p.addLast(sslContext.newHandler(ch.alloc()));
        }
        
        // HTTP codec (handles HTTP/1.1 encoding/decoding)
        p.addLast(new HttpServerCodec());
        
        // Aggregates HTTP message fragments into FullHttpRequest
        p.addLast(new HttpObjectAggregator(maxContentLength));
        
        // Custom handler bridges to Spring MVC
        p.addLast(new SpringMvcBridgeHandler(dispatcherServlet, virtualThreadExecutor));
    }
}
```

### Key Netty Components

| Component | Purpose |
|-----------|---------|
| `HttpServerCodec` | Encodes/decodes HTTP/1.1 messages |
| `HttpObjectAggregator` | Combines chunked requests into single objects |
| `SimpleChannelInboundHandler` | Base class for custom request handlers |
| `FullHttpRequest` | Complete HTTP request with headers and body |
| `FullHttpResponse` | Complete HTTP response |

### Thread Model for Virtual Threads

```java
// Event loop threads (platform threads) - handle NIO
EventLoopGroup bossGroup = new NioEventLoopGroup(1);
EventLoopGroup workerGroup = new NioEventLoopGroup();

// Virtual thread executor for blocking Spring MVC processing
ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

// In handler:
@Override
protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
    virtualThreadExecutor.submit(() -> {
        // All Spring MVC processing happens on virtual thread
        HttpServletRequest servletRequest = adapt(request);
        HttpServletResponse servletResponse = createResponse();
        
        dispatcherServlet.service(servletRequest, servletResponse);
        
        // Write response back to Netty channel
        ctx.writeAndFlush(adaptToNetty(servletResponse));
    });
}
```

## 6. Servlet API Adapter Implementation

### NettyHttpServletRequest

Key methods to implement (subset of HttpServletRequest):

```java
public class NettyHttpServletRequest implements HttpServletRequest {
    private final FullHttpRequest nettyRequest;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    
    // Essential methods for Spring MVC
    public String getMethod() { return nettyRequest.method().name(); }
    public String getRequestURI() { return nettyRequest.uri(); }
    public String getQueryString() { /* parse from URI */ }
    public String getHeader(String name) { return nettyRequest.headers().get(name); }
    public Enumeration<String> getHeaderNames() { /* adapt headers */ }
    public String getParameter(String name) { /* parse query/form params */ }
    public BufferedReader getReader() { /* wrap body content */ }
    public ServletInputStream getInputStream() { /* wrap body content */ }
    
    // Request attributes (used heavily by Spring)
    public Object getAttribute(String name) { return attributes.get(name); }
    public void setAttribute(String name, Object o) { attributes.put(name, o); }
    
    // Many others can return defaults or throw UnsupportedOperationException
}
```

### NettyHttpServletResponse

```java
public class NettyHttpServletResponse implements HttpServletResponse {
    private int status = 200;
    private final HttpHeaders headers = new DefaultHttpHeaders();
    private final ByteArrayOutputStream content = new ByteArrayOutputStream();
    
    public void setStatus(int sc) { this.status = sc; }
    public void setHeader(String name, String value) { headers.set(name, value); }
    public void setContentType(String type) { headers.set(CONTENT_TYPE, type); }
    public PrintWriter getWriter() { return new PrintWriter(content); }
    public ServletOutputStream getOutputStream() { /* wrap content stream */ }
    
    // Convert to Netty response
    public FullHttpResponse toNettyResponse() {
        ByteBuf buf = Unpooled.wrappedBuffer(content.toByteArray());
        FullHttpResponse response = new DefaultFullHttpResponse(
            HTTP_1_1, HttpResponseStatus.valueOf(status), buf);
        response.headers().add(headers);
        response.headers().set(CONTENT_LENGTH, buf.readableBytes());
        return response;
    }
}
```

### Minimal ServletContext

```java
public class NettyServletContext implements ServletContext {
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    
    // Required by Spring MVC
    public String getContextPath() { return contextPath; }
    public Object getAttribute(String name) { return attributes.get(name); }
    public void setAttribute(String name, Object object) { attributes.put(name, object); }
    public ClassLoader getClassLoader() { return getClass().getClassLoader(); }
    
    // Most other methods can throw UnsupportedOperationException
    // or return sensible defaults
}
```

## 7. Virtual Threads Integration Strategy

### Best Practices

1. **Use Virtual Threads for Request Processing**
   - Netty event loops remain platform threads (NIO requirement)
   - Each HTTP request dispatches to a virtual thread
   - All Spring MVC processing (controller, service, repository) runs on virtual thread

2. **Avoid Thread Pinning**
   - Replace `synchronized` with `ReentrantLock` in custom code
   - Use `-Djdk.tracePinnedThreads=short` during testing to detect pinning
   - Update dependencies to Loom-friendly versions (JDBC 42.6.0+, etc.)
   - JEP 491 (Java 24+) eliminates most synchronized pinning

3. **Configure Netty for Virtual Thread Compatibility**
   ```java
   // Disable thread-local caching for allocator (default in newer Netty)
   System.setProperty("io.netty.allocator.useCacheForAllThreads", "false");
   ```

4. **Virtual Thread Executor Configuration**
   ```java
   // Simple approach - unlimited virtual threads
   ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
   
   // With custom thread factory for naming
   ThreadFactory factory = Thread.ofVirtual()
       .name("netty-vt-", 0)
       .factory();
   ExecutorService executor = Executors.newThreadPerTaskExecutor(factory);
   ```

### Experimental: Netty Loom Carrier Mode

Micronaut 4.9+ and Netty 4.2 introduce experimental "loom carrier mode" that runs virtual threads directly on the event loop. This is bleeding-edge and not recommended for initial implementation.

## 8. Performance Considerations

### Why Netty + Virtual Threads Can Be Faster

| Factor | Tomcat | Netty + Virtual Threads |
|--------|--------|-------------------------|
| Thread model | Platform thread per request | Virtual thread per request |
| Memory per connection | ~1MB stack per thread | ~few KB per virtual thread |
| Max connections | Limited by thread pool (100s-1000s) | Millions possible |
| Context switching | OS-level, expensive | JVM-level, cheap |
| I/O handling | Blocking I/O (thread blocked) | Non-blocking I/O + virtual thread continuation |

### Expected Performance Gains

Based on existing benchmarks:
- **Throughput:** 25-75% higher RPS under high concurrency
- **Latency:** 2-10x lower p99 latency under load
- **Memory:** 50-70% reduction in memory per connection
- **CPU:** Lower CPU utilization at equivalent throughput

### Performance Risks

| Risk | Mitigation |
|------|------------|
| Virtual thread pinning | Monitor with JFR, update dependencies |
| Netty buffer management | Use proper release patterns, test for leaks |
| Servlet adapter overhead | Profile and optimize hot paths |
| Blocking in event loop | Ensure all blocking code runs on virtual threads |

## 9. Technology Recommendations

### Required Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| `io.netty:netty-all` | 4.1.100+ | HTTP server, codecs |
| `org.springframework.boot:spring-boot` | 3.2+ | WebServer abstractions |
| `org.springframework:spring-webmvc` | 6.1+ | DispatcherServlet, MVC |
| `jakarta.servlet:jakarta.servlet-api` | 6.0+ | Servlet interfaces |

### Build Configuration

```kotlin
// netty-loom-spring-core/build.gradle.kts
dependencies {
    implementation("io.netty:netty-all:4.1.100.Final")
    implementation("org.springframework.boot:spring-boot:3.2.0")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")
}

// netty-loom-spring-boot-starter/build.gradle.kts
dependencies {
    implementation(project(":netty-loom-spring-core"))
    implementation(project(":netty-loom-spring-mvc"))
    implementation("org.springframework.boot:spring-boot-autoconfigure:3.2.0")
}
```

### Patterns to Follow

1. **Follow Spring Boot's existing server implementations** as reference
   - `TomcatServletWebServerFactory` for factory pattern
   - `TomcatWebServer` for lifecycle management
   
2. **Use Spring's MockHttpServletRequest/Response as implementation guide**
   - Located in `spring-test` module
   - Provides complete implementation reference
   
3. **Follow Netty's recommended HTTP pipeline structure**
   - HttpServerCodec → HttpObjectAggregator → Custom Handler

## 10. Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Servlet API implementation gaps cause runtime errors | Medium | High | Comprehensive testing with real Spring MVC apps; focus on Spring MVC-used subset |
| Virtual thread pinning degrades performance | Medium | Medium | Use JFR monitoring; avoid synchronized; upgrade dependencies |
| Netty memory leaks from improper ByteBuf handling | Medium | High | Use ReferenceCountUtil.release(); enable leak detection in tests |
| Spring Boot version incompatibility | Low | High | Pin to specific Spring Boot version range; test with multiple versions |
| HTTP/1.1 edge cases cause protocol errors | Medium | Medium | Use HttpServerCodec with validation enabled; conformance testing |
| Filter/interceptor ordering issues | Low | Medium | Match Tomcat's behavior; comprehensive integration tests |

## 11. Recommendation

**Recommended Approach: Servlet-Based Integration (Approach A)**

### Rationale

1. **Drop-in Compatibility:** The PRD requires existing Spring MVC applications to work without code changes. Only the servlet-based approach can deliver this.

2. **Proven Pattern:** The [DanielThomas/spring-boot-starter-netty](https://github.com/DanielThomas/spring-boot-starter-netty) project demonstrates this approach is viable, achieving 25-30% performance improvement.

3. **Spring Boot Integration:** Using `ServletWebServerFactory` allows full integration with Spring Boot's auto-configuration, properties, actuator, etc.

4. **Minimal Servlet Implementation:** While the full Servlet API is large, Spring MVC only uses a subset. Focus on:
   - Request/response attributes
   - Headers and parameters
   - Input/output streams
   - Basic servlet context

### Implementation Order

1. **Phase 1:** Core Netty HTTP server with virtual thread executor
2. **Phase 2:** Minimal servlet adapters (request/response/context)
3. **Phase 3:** Spring Boot integration (factory, auto-config)
4. **Phase 4:** Spring MVC integration testing
5. **Phase 5:** Performance optimization and benchmarking

### Key Success Factors

- Start with the minimum viable servlet implementation
- Use Spring's MockHttpServletRequest/Response as reference
- Test against real Spring MVC applications early
- Profile for virtual thread pinning from the start
- Build benchmark suite in parallel with implementation

## 12. References

### Spring Boot Documentation
- [Embedded Web Servers](https://docs.spring.io/spring-boot/how-to/webserver.html)
- [ServletWebServerFactory API](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/web/servlet/server/ServletWebServerFactory.html)
- [AbstractServletWebServerFactory](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/web/servlet/server/AbstractServletWebServerFactory.html)
- [WebServer Interface](https://docs.spring.io/spring-boot/docs/3.0.0/api/org/springframework/boot/web/server/WebServer.html)

### Spring MVC Documentation
- [DispatcherServlet](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet.html)
- [HttpMessageConverters](https://docs.spring.io/spring-framework/reference/web/webmvc/message-converters.html)
- [MockHttpServletRequest Source](https://github.com/spring-projects/spring-framework/blob/main/spring-test/src/main/java/org/springframework/mock/web/MockHttpServletRequest.java)

### Netty Documentation
- [HTTP Server with Netty (Baeldung)](https://www.baeldung.com/java-netty-http-server)
- [HttpServerCodec API](https://netty.io/4.1/api/io/netty/handler/codec/http/HttpServerCodec.html)
- [ChannelPipeline API](https://netty.io/4.0/api/io/netty/channel/ChannelPipeline.html)

### Virtual Threads Resources
- [Virtual Thread Pinning - Todd Ginsberg](https://todd.ginsberg.com/post/java/virtual-thread-pinning/)
- [JEP 491 - Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)
- [Quarkus Virtual Thread Guide](https://quarkus.io/guides/virtual-threads)
- [Spring Blog - Embracing Virtual Threads](https://spring.io/blog/2022/10/11/embracing-virtual-threads/)

### Existing Implementations
- [DanielThomas/spring-boot-starter-netty](https://github.com/DanielThomas/spring-boot-starter-netty) - Netty servlet bridge for Spring Boot
- [bigpuritz/netty-servlet-bridge](https://github.com/bigpuritz/netty-servlet-bridge) - Servlet API for Netty
- [Jotschi/netty-loom-experiment](https://github.com/Jotschi/netty-loom-experiment) - Netty + Loom experiments
- [Helidon Nima](https://helidon.io/nima) - Virtual threads HTTP server

### Performance Benchmarks
- [Choosing a Spring Boot Server for 2025](https://junkangworld.com/blog/choosing-a-spring-boot-server-for-2025-my-test-results)
- [Netty vs Tomcat Comparison](https://devcookies.medium.com/tomcat-vs-netty-why-modern-applications-are-moving-away-from-traditional-servers-6a0338c00901)
- [Helidon 4 Virtual Threads Performance](https://www.infoq.com/articles/helidon-4-adopts-virtual-threads/)
- [Micronaut Loom Carrier Mode](https://micronaut.io/2025/06/30/transitioning-to-virtual-threads-using-the-micronaut-loom-carrier/)
