# Netty-Loom-Spring: Deep Analysis Report

**Date**: 2026-03-28
**Build Status**: PASSING (all tests green)
**Version**: 0.1.0-SNAPSHOT | Java 24 (preview) | Netty 4.1.130 | Spring Boot 3.4.13

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Module Analysis](#2-module-analysis)
3. [Implementation Issues](#3-implementation-issues)
4. [Test Coverage Gaps](#4-test-coverage-gaps)
5. [Servlet API Completeness](#5-servlet-api-completeness)
6. [Auto-Configuration Gaps](#6-auto-configuration-gaps)
7. [Security Concerns](#7-security-concerns)
8. [Performance Concerns](#8-performance-concerns)
9. [Recommendations](#9-recommendations)

---

## 1. Architecture Overview

```
netty-loom-spring-boot-starter   (auto-config, WebServer factory)
  |-- netty-loom-spring-mvc      (Servlet API adapters, DispatcherServlet bridge)
  |     |-- netty-loom-spring-core  (Netty server, pipeline, virtual threads)
  |
example-netty / example-tomcat   (empty scaffolds)
```

**Request flow**: Netty channel -> HttpServerCodec -> HttpObjectAggregator -> IdleStateHandler -> HttpRequestDecoder -> HttpResponseEncoder -> RequestDispatcher (virtual thread) -> DispatcherServletHandler -> Spring DispatcherServlet -> Controller

ArchUnit enforces that `core` has zero Spring/Jakarta dependencies.

---

## 2. Module Analysis

### 2.1 Core Module (20 source files, 5 test files)

| Area | Status | Notes |
|------|--------|-------|
| Server lifecycle (start/stop) | OK | Atomic state machine, graceful shutdown |
| Pipeline setup | OK | Correct handler ordering |
| HTTP request/response conversion | OK | Proper ByteBuf handling |
| Virtual thread dispatch | Partial | Executor never shut down (see Issue #1) |
| Idle connection handling | Broken | IdleStateHandler added but events not handled (see Issue #6) |
| Keep-alive | Missing | Connection: close hardcoded (see Issue #2) |

### 2.2 MVC Module (8 source files, 1 test file)

| Area | Status | Notes |
|------|--------|-------|
| DispatcherServletHandler bridge | OK | Clean adapter, 9 lines |
| NettyHttpServletRequest | Partial | Covers GET/POST/PUT/PATCH/DELETE; missing sessions, cookies, auth, async, multipart |
| NettyHttpServletResponse | Partial | Basic headers + body; no cookies, `reset()` doesn't clear output stream |
| NettyServletContext | Partial | Attribute storage + resource loading only |
| NettyServletOutputStream | OK | Simple ByteArrayOutputStream wrapper |

### 2.3 Starter Module (3 source files, 8 test files)

| Area | Status | Notes |
|------|--------|-------|
| Auto-configuration | OK | Correct conditionals and ordering |
| NettyWebServerFactory | OK | Minimal, delegates to NettyWebServer |
| NettyWebServer | OK | Proper servlet init + server lifecycle |
| Smoke tests (REST) | Good | 19 tests covering all HTTP methods, DTOs, status codes |
| Smoke tests (virtual threads) | Good | Verifies Thread.isVirtual() |

### 2.4 Example Modules

Both `example-netty` and `example-tomcat` have only `build.gradle.kts` with no source code. They compile but are empty scaffolds.

---

## 3. Implementation Issues

### Issue #1: Virtual Thread Executor Never Shut Down [HIGH] ✅ FIXED

**File**: `core/.../pipeline/HttpServerNettyPipelineConfigurer.java`

`Executors.newVirtualThreadPerTaskExecutor()` is created in the constructor but never closed. When `NettyServer.stop()` is called, in-flight virtual threads may continue running. This causes:
- Resource leaks
- Delayed JVM shutdown
- Potential orphaned I/O operations

**Fix**: Pass the executor to `NettyServer` or register a shutdown hook.

**Resolution**: Executor ownership moved to `NettyWebServer`, which creates it in the constructor and shuts it down in `stop()` with `awaitTermination(30s)`. `HttpServerNettyPipelineConfigurer` now accepts the executor via constructor injection.

### Issue #2: HTTP Keep-Alive Disabled [HIGH] ✅ FIXED

**File**: `core/.../http/DefaultNettyHttpResponseConverter.java:27`

Every response hardcodes `Connection: close`. This forces a new TCP connection per request, which is a significant performance regression vs Tomcat/Jetty defaults. HTTP/1.1 keep-alive is the norm.

**Fix**: Respect the client's `Connection` header. Default to keep-alive for HTTP/1.1.

### Issue #3: HttpMethod Enum Missing HEAD and OPTIONS [HIGH] ✅ FIXED

**File**: `core/.../http/HttpMethod.java`

The enum only defines GET, POST, PUT, PATCH, DELETE. **HEAD and OPTIONS are missing.** `DefaultNettyHttpRequestConverter` calls `HttpMethod.valueOf(msg.method().name())`, which throws `IllegalArgumentException` for any unsupported method. This means:
- **CORS preflight requests (OPTIONS)** will crash with a 500 error
- **HEAD requests** (used by health checks, caching) will crash with a 500 error
- **TRACE, CONNECT** will also crash, though these are less common

**Fix**: Add at minimum HEAD and OPTIONS to the enum. Consider a fallback instead of `valueOf()`.

### Issue #4: server.address Property Silently Ignored [HIGH] ✅ FIXED

**File**: `starter/.../NettyLoomAutoConfiguration.java:29-31` + `starter/.../server/NettyWebServer.java`

The auto-configuration reads `server.address` and calls `factory.setAddress()`, but `NettyWebServerFactory.getWebServer()` only passes `getPort()` to `NettyWebServer`. The address is never forwarded. `NettyServer.start()` calls `bootstrap.bind(config.port())` without an address, always binding to `0.0.0.0` (all interfaces). Setting `server.address=127.0.0.1` has no effect.

**Fix**: Pass the address through to `NettyServerConfig` and use `bootstrap.bind(address, port)`.

### Issue #5: No Request Timeout [MEDIUM] ✅ FIXED

**File**: `core/.../handler/RequestDispatcher.java`

`requestHandler.handle(request)` has no timeout. A slow controller will block a virtual thread forever. While virtual threads are cheap, unbounded accumulation under load will eventually exhaust memory.

**Fix**: Wrap with `CompletableFuture.orTimeout()` or a scheduled cancel.

### Issue #6: AutoConfiguration.imports Has .java Suffix [MEDIUM] ✅ FIXED

**File**: `starter/src/main/resources/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

The file contains:
```
io.github.azholdaspaev.nettyloom.autoconfigure.NettyLoomAutoConfiguration.java
```
The `.java` suffix should not be there. Spring Boot expects a fully qualified class name without file extension. This works currently likely because Spring Boot is tolerant of it, but it's incorrect and may break in future versions.

**Fix**: Remove the `.java` suffix.

### Issue #7: State Machine Race Condition [MEDIUM] ✅ FIXED

**File**: `core/.../server/NettyServer.java`

`start()` does `state.compareAndSet(CREATED, STARTING)` then proceeds with binding. If two threads call `start()` simultaneously, only one wins the CAS, but the losing thread gets an `IllegalStateException` with potentially confusing message since the state has already moved to STARTING.

This is safe (no corruption) but the error message could be clearer.

### Issue #8: Idle State Handler Has No Effect [MEDIUM]

**File**: `core/.../pipeline/HttpServerNettyPipelineConfigurer.java`

`IdleStateHandler` is added to the pipeline with a configurable timeout, but **no handler processes `IdleStateEvent`**. Idle connections will never be closed. This defeats the purpose of the idle timeout configuration.

**Fix**: Add a handler that closes the channel on `IdleStateEvent`.

### Issue #9: DefaultNettyServletContext Missing Critical Method Overrides [MEDIUM]

**File**: `mvc/.../servlet/DefaultNettyServletContext.java` + `NettyServletContext.java`

The `NettyServletContext` interface defines all `ServletContext` methods as `default` throwing `NotImplementedException`. `DefaultNettyServletContext` only overrides a subset. Several commonly-called methods are **not overridden** and will throw at runtime:
- `getContextPath()` — called by Spring MVC during URL resolution
- `removeAttribute(String)` — called during context cleanup
- `getMajorVersion()` / `getMinorVersion()` — checked by Spring for servlet API version
- `getServletContextName()` — used in logging
- `setInitParameter()` — may be called during Spring Boot initialization
- `getRequestDispatcher()` — needed for forward/include

Currently the smoke tests pass because the happy path doesn't trigger these. Any Spring feature that calls these methods will crash.

**Fix**: Override at minimum `getContextPath()` (return `""`), `removeAttribute()`, `getMajorVersion()`/`getMinorVersion()`, and `getServletContextName()`.

### Issue #10: NettyHttpServletRequest Hardcodes Server Info [LOW]

**File**: `mvc/.../servlet/NettyHttpServletRequest.java`

- `getServerName()` returns `"localhost"`
- `getServerPort()` returns `80`
- `getRemoteAddr()` / `getLocalAddr()` return `"127.0.0.1"`

These should be extracted from the Netty channel and HTTP Host header.

### Issue #11: sendError Doesn't Write Error Body [LOW]

**File**: `mvc/.../servlet/NettyHttpServletResponse.java:142-148`

`sendError(int sc, String msg)` only sets the status code, ignoring the message. Spring's `BasicErrorController` and `DefaultHandlerExceptionResolver` rely on `sendError` to produce a proper error response body. The message parameter is silently discarded.

**Fix**: Write a minimal HTML or JSON error body when `sendError` is called with a message.

### Issue #12: POST Form Body Parameters Not Parsed [MEDIUM]

**File**: `mvc/.../servlet/NettyHttpServletRequest.java:45-63`

Only query string parameters are parsed. For `application/x-www-form-urlencoded` POST requests, parameters should also be extracted from the request body per the Servlet specification. Currently `getParameter()` returns null for form POST fields.

**Fix**: When Content-Type is `application/x-www-form-urlencoded`, parse the request body using `QueryStringDecoder` and merge into the parameters map.

### Issue #13: reset() Doesn't Clear Output Stream [MEDIUM]

**File**: `mvc/.../servlet/NettyHttpServletResponse.java:199-202`

`reset()` clears status and headers but does not reset the `NettyServletOutputStream`. Any bytes already written to the output stream will still appear in the final response. Spring calls `reset()` before writing error responses.

**Fix**: Add a `reset()` method to `NettyServletOutputStream` and call it from `HttpServletResponse.reset()`.

### Issue #14: isCommitted() Always Returns False [MEDIUM]

**File**: `mvc/.../servlet/NettyHttpServletResponse.java:111-113`

`isCommitted()` always returns `false`. Spring checks this before writing error responses, redirects, and forwarded responses. Since the response is fully buffered, it's technically never "committed" until `asNettyHttpResponse()` is called, but after `sendError`/`sendRedirect` it should logically be committed to prevent further modification.

### Issue #15: getInputStream/getReader Don't Enforce Mutual Exclusion [LOW]

**File**: `mvc/.../servlet/NettyHttpServletRequest.java:150-181`

Per Servlet spec, calling `getInputStream()` after `getReader()` (or vice versa) should throw `IllegalStateException`. Both methods can be called freely and each creates a new stream from the same byte array, which also means multiple calls return independent streams.

### Issue #16: ChannelFutureListener.CLOSE Redundant with Connection: close [LOW]

**File**: `core/.../handler/RequestDispatcher.java:36,43` + `core/.../http/DefaultNettyHttpResponseConverter.java:27`

Both the `Connection: close` header and `ChannelFutureListener.CLOSE` force connection termination. When keep-alive is eventually implemented, `ChannelFutureListener.CLOSE` must be removed from `RequestDispatcher` to allow connection reuse - it's not just the response header that needs to change.

### Issue #17: NettyWebServer Exception Handler Returns Empty 500 [LOW]

**File**: `starter/.../server/NettyWebServer.java:54`

The exception handler lambda `(_, _) -> DefaultNettyHttpResponse.builder().statusCode(500).build()` returns a 500 response with no body, no Content-Type, and no error message. Clients receive an empty response with just a status code. Spring Boot's `BasicErrorController` is not invoked for these.

### Issue #18: No Content-Type Default on Response [LOW]

**File**: `mvc/.../servlet/NettyHttpServletResponse.java`

`getContentType()` can return null if never set, but `asNettyHttpResponse()` doesn't add a Content-Type header unless `setContentType()` was explicitly called. Some frameworks expect a default.

---

## 4. Test Coverage Gaps

### Core Module

| Missing Test | Severity | Description |
|-------------|----------|-------------|
| Concurrent request handling | HIGH | No test verifies behavior under concurrent load |
| Server shutdown with in-flight requests | HIGH | No test for graceful drain of active requests |
| Large request body (> maxContentLength) | MEDIUM | 413 behavior untested |
| Malformed HTTP requests | MEDIUM | Bad request lines, invalid headers |
| HEAD/OPTIONS requests | HIGH | `HttpMethod.valueOf()` throws for HEAD, OPTIONS — server returns 500 |
| HTTP method conversion for unknown methods | MEDIUM | No graceful fallback for TRACE, CONNECT, etc. |
| IdleStateHandler timeout behavior | LOW | Handler exists but no test verifies timeout |

### MVC Module

| Missing Test | Severity | Description |
|-------------|----------|-------------|
| NettyHttpServletRequest | HIGH | **Zero unit tests** for the 449-line servlet request wrapper |
| NettyHttpServletResponse | HIGH | **Zero unit tests** for the 212-line servlet response wrapper |
| DispatcherServletHandler | MEDIUM | No unit test; only covered indirectly by smoke tests |
| NettyServletOutputStream | LOW | No unit test (trivial wrapper) |

### Starter Module

| Missing Test | Severity | Description |
|-------------|----------|-------------|
| server.address binding | HIGH | Address property accepted but silently ignored — not tested |
| Error responses (4xx/5xx) | HIGH | Only 400 tested; no 404, 405, 500 tests |
| Exception handler behavior | HIGH | No test for controller exceptions |
| Binary request/response | MEDIUM | Only JSON tested |
| Multipart/file upload | MEDIUM | Not tested (and not implemented) |
| CORS | MEDIUM | Not tested |
| Request/response streaming | LOW | Not applicable to current buffered approach |

---

## 5. Servlet API Completeness

The MVC module implements the bare minimum of the Servlet API. Below is a completeness matrix:

### HttpServletRequest

| Feature | Status |
|---------|--------|
| Method, URI, query string | Implemented |
| Headers (get/enumerate) | Implemented |
| Parameters (query string only) | Partial (form POST body params not parsed) |
| Request body / InputStream | Implemented |
| Attributes | Implemented |
| Content-Type / Content-Length | Implemented |
| Cookies | Not implemented (returns empty) |
| Sessions | Not implemented (returns null) |
| Authentication (getUserPrincipal, isUserInRole) | Not implemented |
| Locale negotiation | Not implemented (returns default) |
| Request dispatching (forward/include) | Not implemented |
| Async context | Not implemented |
| Multipart / file upload | Not implemented |
| HTTP upgrade | Not implemented |
| Trailers | Not implemented |

### HttpServletResponse

| Feature | Status |
|---------|--------|
| Status code | Implemented |
| Headers (set/add) | Implemented |
| Response body / OutputStream | Implemented |
| PrintWriter | Implemented |
| Content-Type | Implemented |
| Cookies | Not implemented (no-op) |
| sendError (with body) | Partial (status only, message ignored) |
| sendRedirect | Partial (sets 302 + Location header, but `isCommitted()` stays false) |
| Buffer management (flush/reset) | Partial (`reset()` doesn't clear output stream) |
| Content-Length | Implemented via `setContentLength`/`setContentLengthLong` |

### ServletContext

| Feature | Status |
|---------|--------|
| Attributes | Implemented |
| Init parameters | Implemented |
| Resource loading | Implemented |
| Context path | Implemented |
| Servlet/filter registration | Stub (no-op registrations) |
| Named dispatchers | Not implemented |
| MIME types | Not implemented |
| Event listeners | Not implemented |
| Session config | Not implemented |
| JSP config | Not implemented |

---

## 6. Auto-Configuration Gaps

The auto-configuration is functional but minimal. Missing capabilities:

| Feature | Status | Notes |
|---------|--------|-------|
| Port configuration | OK | Reads from `server.port` |
| Address binding | OK | Reads from `server.address` |
| Worker thread count | Missing | Not configurable via properties |
| Max content length | Missing | Hardcoded to 2MB |
| Max header size | Missing | Hardcoded to 8KB |
| Idle timeout | Missing | Hardcoded to 60s |
| Keep-alive | Missing | Always disabled |
| SSL/TLS | Missing | No HTTPS support |
| HTTP/2 | Missing | HTTP/1.1 only |
| Compression | Missing | No gzip/deflate |
| Access logging | Missing | No request logging |
| Metrics (Micrometer) | Missing | Dependencies present but unused |
| Tracing (Micrometer) | Missing | Dependencies present but unused |
| Graceful shutdown config | Missing | No drain period |
| Custom error pages | Missing | No error page registration |
| Actuator integration | Missing | Health, info endpoints not integrated |

---

## 7. Security Concerns

| Concern | Severity | Details |
|---------|----------|--------|
| No HTTPS/TLS support | HIGH | All traffic is plaintext |
| No CORS handling | MEDIUM | Cross-origin requests unprotected |
| No request size limits enforcement feedback | MEDIUM | 413 response exists but not tested |
| No header injection protection | LOW | Headers copied as-is from Netty |
| No rate limiting | LOW | Acceptable for MVP |
| Spring Security compileOnly | INFO | Dependency exists but integration is untested |

---

## 8. Performance Concerns

| Concern | Impact | Details |
|---------|--------|--------|
| Connection: close on every response | HIGH | Forces new TCP handshake per request; ~3x latency vs keep-alive |
| Full response buffering | MEDIUM | Entire response body buffered in memory (NettyServletOutputStream) |
| Full request body buffering | MEDIUM | HttpObjectAggregator buffers entire request before processing |
| No connection pooling | MEDIUM | Direct consequence of no keep-alive |
| No response compression | LOW | Larger payloads over the wire |
| New QueryStringDecoder per request | LOW | Acceptable overhead |

---

## 9. Recommendations

### Priority 1 (Critical for usability)

1. **Add HEAD and OPTIONS to HttpMethod enum** - Without these, CORS preflight and health checks crash with 500 errors. This is a blocking bug for any real-world use.
2. **Fix server.address being silently ignored** - The address is set on the factory but never passed through. Server always binds to all interfaces regardless of config.
3. **Enable HTTP keep-alive** - Remove hardcoded `Connection: close`. Respect client headers. This is the single biggest performance improvement.
4. **Shut down virtual thread executor** - Add executor lifecycle management to prevent resource leaks on shutdown.
5. **Fix AutoConfiguration.imports** - Remove `.java` suffix from the class name.
6. **Add idle connection closer** - Handle `IdleStateEvent` to actually close idle connections.
7. **Override missing critical ServletContext methods** - `getContextPath()`, `removeAttribute()`, `getMajorVersion()`/`getMinorVersion()` all throw `NotImplementedException` and are called by common Spring features.

### Priority 2 (Important for correctness)

5. **Add unit tests for NettyHttpServletRequest** - 449 lines with zero direct tests. Cover header parsing, parameter extraction, body reading.
6. **Add unit tests for NettyHttpServletResponse** - 212 lines with zero direct tests. Cover header management, status codes, output stream.
7. **Add error handling tests** - Test 404, 405, 500 responses through the full stack.
8. **Add request timeout** - Prevent unbounded virtual thread accumulation.
9. **Extract server info from channel** - Server name, port, remote address should come from the actual connection, not hardcoded values.

### Priority 3 (Important for production readiness)

10. **Add SSL/TLS support** - Required for any production deployment.
11. **Add configurable properties** - Expose Netty tuning knobs (threads, timeouts, limits) via `application.properties`.
12. **Implement sendError with body** - Required for Spring's default error handling.
13. **Parse POST form body parameters** - `application/x-www-form-urlencoded` body params are silently ignored.
14. **Fix reset() to clear output stream** - Spring calls `reset()` before writing error responses.
15. **Add Micrometer metrics** - Dependencies are already declared; wire them up.
16. **Add example application code** - Both example modules are empty scaffolds.
17. **Remove `ChannelFutureListener.CLOSE` from RequestDispatcher** when implementing keep-alive - both the header and listener close the connection.

### Priority 4 (Nice to have)

18. **HTTP/2 support** - Via Netty's `Http2MultiplexCodec`.
19. **Response compression** - Via Netty's `HttpContentCompressor`.
20. **Session support** - At minimum in-memory sessions for Spring Security.
21. **Streaming response support** - Chunked transfer encoding instead of full buffering.
22. **WebSocket support** - Via Netty's WebSocket handlers.
23. **Enforce getInputStream/getReader mutual exclusion** - Per Servlet spec.
