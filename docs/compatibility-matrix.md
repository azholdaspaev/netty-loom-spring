# Compatibility matrix

What the servlet bridge implements, method by method. Verified against the source at
`0.1.0-SNAPSHOT`; see [README](../README.md) for the summary and
[docs/configuration.md](configuration.md) for the property reference.

**Status values**

| Status | Meaning |
| --- | --- |
| `works` | Implemented and behaves as the servlet spec requires |
| `partial` | Implemented with a deviation you need to know about |
| `none` | Not implemented — the call returns a stub value or throws |
| `ignored` | Accepted without complaint and has no effect. **No warning, no failure** |
| `fails startup` | Rejected loudly at startup with a message naming the property |

---

## Programming model

| Feature | Status | Notes |
| --- | --- | --- |
| `@RestController` / `@Controller`, request mapping | `works` | |
| `@RequestBody` / `@ResponseBody`, message converters | `works` | |
| Content negotiation, `@RequestParam`, `@PathVariable` | `works` | |
| `HandlerInterceptor`, `@ControllerAdvice` | `works` | |
| Spring Security | `works` | It is a servlet `Filter` with its own `SecurityContext`; it does not depend on the container auth methods, which are stubs |
| Actuator | untested | No compatibility test module yet ([#25](https://github.com/azholdaspaev/netty-loom-spring/issues/25)) |
| `@Async` MVC, `Callable` return values | `none` | Routes through `startAsync()` |
| Welcome page (`index.html`) | `none` | Boot's `forward:index.html` needs a `RequestDispatcher`, which is `null`; returns 500 with a static `index.html` present ([#59](https://github.com/azholdaspaev/netty-loom-spring/issues/59)), 404 without one |

## Errors

| Feature | Status | Notes |
| --- | --- | --- |
| `sendError(int)` | `partial` | Sets the status, clears the body, drops `Content-Length`. The client gets a bare status line with a zero-length body |
| `sendError(int, String)` | `partial` | Identical to the single-argument form — **the message is discarded** |
| Error-page dispatch to `/error` | `none` | `getErrorPages()` is never read and there is no `ERROR` dispatch, so `BasicErrorController` never runs for a failed request. Every Spring-generated 400/404/405/415 has an empty body ([#38](https://github.com/azholdaspaev/netty-loom-spring/issues/38)). A direct `GET /error` still works, being an ordinary mapping |
| `spring.web.error.*` | `ignored` | Boot 4 moved these from `server.error.*`; neither is honoured |
| Uncaught controller exception | `partial` | 500 with a plain-text `Internal Server Error` body and `Connection: close`. If bytes were already streamed, the connection is closed with no response |
| Out-of-context URI (with a context path set) | `partial` | A bare 404 returned before the filter chain — no filter, listener or Security rule sees it. Deliberate; see [ADR 0001](adr/0001-server-properties-namespace.md) |

## Request

| Feature | Status | Notes |
| --- | --- | --- |
| `getRemoteAddr/Host/Port`, `getLocalAddr/Name/Port` | `works` | `getRemoteHost` returns the IP — never a reverse-DNS name. `getLocalName` returns an IP, not a hostname. An unresolvable `SocketAddress` degrades to `""` / `0` |
| `getScheme()`, `isSecure()` | `partial` | Derived from the presence of an `SslHandler`, so in practice always `http` / `false` — TLS is unimplemented and forwarded headers are not consulted |
| `getServerName()`, `getServerPort()` | `works` | Parsed from the `Host` header, IPv6 bracketed; falls back to the local address when `Host` is absent |
| `getHeader*`, `getHeaderNames` | `works` | `getDateHeader` throws `IllegalArgumentException` on an unparseable date (surfaces as 400) |
| `getCookies()` | `partial` | `ServerCookieDecoder.STRICT`. Returns `null`, not an empty array, when there are none or the header is malformed |
| `getParameter*` | `partial` | Query and form-body parameters merged, query first. **Netty's `QueryStringDecoder` limits apply: at most 1024 parameters, the rest silently dropped** ([#122](https://github.com/azholdaspaev/netty-loom-spring/issues/122)); `;` is a parameter separator; `+` decodes to a space |
| `getParameterMap()` | `partial` | Unmodifiable map, but the `String[]` values are live ([#120](https://github.com/azholdaspaev/netty-loom-spring/issues/120)) |
| `getInputStream()` / `getReader()` | `partial` | Over the fully aggregated body; mutually exclusive, as the spec requires. `getReader()` defaults to **UTF-8**, where the spec says ISO-8859-1. The stream is cached, so the body cannot be re-read |
| Body consumption by `getParameter` | `partial` | Reading parameters does **not** consume the body here, unlike Tomcat — code that works here can break on Tomcat |
| `setCharacterEncoding` | `partial` | Silently ignored once parameters are parsed or `getReader()` has been called |
| Query-string charset | `partial` | Always decoded as UTF-8, independent of the request encoding |
| Request body size | `partial` | Buffered whole in memory, capped at 1 MiB; over-limit is answered 413 ([#51](https://github.com/azholdaspaev/netty-loom-spring/issues/51)) |
| `getLocale*` | `works` | Full `Accept-Language` q-value parsing |
| `getRequestDispatcher(path)` | `none` | Returns **`null`**, so callers NPE at their own call site |
| `getPathInfo()`, `getPathTranslated()` | `none` | Always `null`; `getServletPath()` returns the whole context-relative path |
| `getParts()`, `getPart(name)` | `none` | Always empty / `null` ([#14](https://github.com/azholdaspaev/netty-loom-spring/issues/14)) |
| `startAsync()` | `none` | Returns **`null`** where the spec requires `IllegalStateException` ([#18](https://github.com/azholdaspaev/netty-loom-spring/issues/18)) |
| `upgrade(Class)` | `none` | Returns `null` — no WebSocket, no HTTP upgrade |
| `getUserPrincipal`, `getRemoteUser`, `getAuthType` | `none` | Always `null` |
| `isUserInRole` | `none` | Always `false` |
| `authenticate`, `login`, `logout` | `none` | Silent no-ops — `login` reports no failure, so a caller believes it succeeded |
| `getRequestId()`, `getProtocolRequestId()` | `partial` | Return `""` where the spec requires a unique id ([#116](https://github.com/azholdaspaev/netty-loom-spring/issues/116)) |
| `getDispatcherType()` | `partial` | Always `REQUEST` |

## Response

| Feature | Status | Notes |
| --- | --- | --- |
| `getOutputStream()` / `getWriter()` | `partial` | Streams as `Transfer-Encoding: chunked` once the body outgrows the 8 KB buffer or `flushBuffer()` is called; otherwise a single `Content-Length` response. Both can currently be obtained on the same response ([#118](https://github.com/azholdaspaev/netty-loom-spring/issues/118)) |
| Framing | `works` | Matches Tomcat; see [docs/configuration.md](configuration.md#response-framing) for the full table |
| Backpressure | `works` | A handler producing faster than the client reads blocks on its virtual thread, bounded by a fixed 60s write-stall timeout |
| `setBufferSize`, `getBufferSize` | `works` | Default 8192; `setBufferSize` throws once anything is written |
| `flushBuffer()` | `works` | Commits, as the spec requires: the head goes on the wire, so `setStatus` and the header mutators no-op afterwards and `resetBuffer` throws |
| `reset()`, `resetBuffer()` | `partial` | Both throw once the head is on the wire. `resetBuffer` drops a still-bound `PrintWriter` ([#117](https://github.com/azholdaspaev/netty-loom-spring/issues/117)) |
| Headers, `setStatus` | `works` | Silently no-op after commit, as the spec requires |
| `addCookie` | `partial` | Maps name, value, path, domain, max-age, `Secure`, `HttpOnly`, `SameSite`, `Partitioned`. **`Expires`, `Comment` and `Version` are silently dropped** |
| `CookieSameSiteSupplier` beans | `works` | An explicit cookie attribute wins over any supplier |
| `sendRedirect` | `partial` | Sets `Location` verbatim — no relative-to-absolute resolution, and the buffered body is **not** discarded, unlike `sendError` |
| `encodeURL`, `encodeRedirectURL` | `none` | Identity functions — no URL session rewriting |
| `setLocale` | `ignored` | No-op; `getLocale()` returns the JVM default, so `Content-Language` is never emitted |
| `Date` and `Server` response headers | `none` | Neither is ever emitted. Tomcat always sends `Date` |
| `setCharacterEncoding(null)` | `partial` | Throws `IllegalArgumentException`; Servlet 6.1 says it should reset to the default |
| `setWriteListener` | `ignored` | No-op, so `onWritePossible()` never fires and a `WriteListener`-based writer stalls rather than failing fast |
| `setReadListener` | `none` | Throws `UnsupportedOperationException` |

## Sessions

| Feature | Status | Notes |
| --- | --- | --- |
| Creation, lookup, invalidation | `works` | In-memory; ids are 16 random bytes of `SecureRandom` as uppercase hex |
| `server.servlet.session.timeout` | `works` | Second resolution; `0` or less means never expires. Lazy 60s sweeper thread plus exact expiry on lookup |
| `server.servlet.session.cookie.*` | `works` | Name, domain, path, `http-only`, `secure`, `max-age`, `same-site`, `partitioned`. Frozen after startup. `setComment` is accepted and does nothing |
| Cookie `Secure` behind a TLS proxy | `partial` | Derived from the real connection, which is plaintext behind a terminating proxy — set `server.servlet.session.cookie.secure=true` explicitly (CWE-614, [#50](https://github.com/azholdaspaev/netty-loom-spring/issues/50)) |
| `changeSessionId()` | `partial` | Rotates and re-emits the cookie. After commit it still rotates but the new `Set-Cookie` is dropped, stranding the client on a dead id |
| Session teardown on shutdown | `works` | Every session is invalidated and unbound, so `@PreDestroy` and `@SessionScope` callbacks run |
| URL rewriting | `none` | `encodeURL` is the identity; `isRequestedSessionIdFromURL()` is always `false` |
| `server.servlet.session.tracking-modes` other than `cookie` | `fails startup` | An empty set is legal and disables the cookie |
| `server.servlet.session.persistent=true` | `fails startup` | Use Spring Session for a durable store |
| `server.servlet.session.store-dir` | `ignored` | |
| Distributed sessions | `none` | Use Spring Session |

## Listeners

All registered through `ServletContext.addListener`, so `@WebListener`,
`ServletListenerRegistrationBean` and `HttpSessionEventPublisher` all work.

| Listener | Status |
| --- | --- |
| `ServletContextListener` | `works` |
| `ServletContextAttributeListener` | `works` |
| `ServletRequestListener` | `works` |
| `ServletRequestAttributeListener` | `works` |
| `HttpSessionListener` | `works` |
| `HttpSessionAttributeListener` | `works` |
| `HttpSessionIdListener` | `works` |
| `HttpSessionBindingListener` | `works` — fired on both bind and unbind |
| `HttpSessionActivationListener` | `none` — sessions are never passivated or activated, so `sessionDidActivate` never fires, silently |

Those seven `addListener` types are the complete set the spec defines; anything else is rejected
with `IllegalArgumentException`, as on Tomcat. `AsyncListener` is registered through
`AsyncContext`, which does not exist here.

## Filters and servlets

| Feature | Status | Notes |
| --- | --- | --- |
| `addFilter(name, Filter instance)` | `works` | This is what `FilterRegistrationBean` and Boot's own filters use |
| `addFilter(name, Class)` / `addFilter(name, String className)` | `ignored` | Registration is recorded and **silently skipped at request time** — the filter is never instantiated and never runs |
| Filter URL-pattern mappings | `works` | Servlet-spec §12.2 matching (exact, `/*`, `/prefix/*`, `*.ext`) against the context-relative path |
| Filter servlet-name mappings | `ignored` | Logged at WARN and discarded |
| Filter dispatcher types | `partial` | Recorded and checked, but `getDispatcherType()` is always `REQUEST`, so `FORWARD` / `INCLUDE` / `ERROR` / `ASYNC` mappings can never match |
| Filter ordering | `works` | Registration order, which is Boot's `@Order` resolution |
| `FilterConfig` init parameters | `none` | `setInitParameter` records them; `getInitParameter` always returns `null` |
| `Filter.destroy()`, `Servlet.destroy()` | `none` | Never called at shutdown ([#103](https://github.com/azholdaspaev/netty-loom-spring/issues/103)) |
| `addServlet(name, ...)` — all three overloads | `ignored` | Only the class name is recorded; the instance is discarded. The dispatch chain terminates at `DispatcherServlet` unconditionally, so a `ServletRegistrationBean` registers and is **never invoked** |
| `setLoadOnStartup`, `setServletSecurity`, `setRunAsRole`, `setMultipartConfig`, `setAsyncSupported` | `ignored` | Accepted, no effect |
| `spring.mvc.servlet.path` | `ignored` | `DispatcherServlet` receives every in-context request |

## ServletContext

| Feature | Status | Notes |
| --- | --- | --- |
| `getContextPath`, `getAttribute*`, `getInitParameter` | `works` | `setInitParameter` is put-if-absent and returns `false` on a duplicate |
| `getResource`, `getResourceAsStream`, `getResourcePaths`, `getRealPath` | `none` | All return `null` ([#15](https://github.com/azholdaspaev/netty-loom-spring/issues/15)). `getResourcePaths` returns `null` rather than an empty set |
| `getMimeType`, `getRequestDispatcher`, `getNamedDispatcher`, `getContext`, `addJspFile`, `createServlet`, `createFilter`, `getJspConfigDescriptor`, `declareRoles`, `getVirtualServerName`, `get/setRequestCharacterEncoding`, `get/setResponseCharacterEncoding` | `none` | Throw `UnsupportedOperationException`, which surfaces as **501** if it reaches the pipeline unwrapped, or 500 once Spring wraps it |
| `getServletContextName()` | `ignored` | Hardcoded, so `server.servlet.application-display-name` has no effect ([#86](https://github.com/azholdaspaev/netty-loom-spring/issues/86)) |
| `getServerInfo()` | `works` | Returns `Netty-Loom` |
| `getMajorVersion` / `getMinorVersion` | `works` | Reports Servlet 6.0 |

## Protocol and transport

| Feature | Status | Notes |
| --- | --- | --- |
| HTTP/1.1, keep-alive, pipelining | `works` | Responses leave in request order |
| HTTP/1.0 | `partial` | Streamed responses are close-delimited rather than chunked |
| `HEAD`, `OPTIONS` | `works` | Via `HttpServlet`'s own handling |
| Server-wide `OPTIONS *` | `none` | [#58](https://github.com/azholdaspaev/netty-loom-spring/issues/58) |
| epoll / kqueue / NIO transports | `works` | Auto-selected; see `server.netty.transport` |
| Graceful shutdown | `works` | Two-phase drain. Note `server.shutdown=immediate` is ignored — it always drains ([#87](https://github.com/azholdaspaev/netty-loom-spring/issues/87)) |
| Slow-loris protection | `works` | Per-connection read timeout measuring the client, not the handler |
| Frame-size limits | `partial` | Fixed, not configurable ([#42](https://github.com/azholdaspaev/netty-loom-spring/issues/42)) |
| Bounded outbound queue | `none` | A pipelining client that never reads accumulates unbounded buffer — 5.7 MB queued out for 888 bytes in, measured ([#88](https://github.com/azholdaspaev/netty-loom-spring/issues/88)). The 60s write-stall timeout above bounds this in time, not in space |
| Handler execution deadline | `none` | A handler that never returns holds its connection until shutdown ([#43](https://github.com/azholdaspaev/netty-loom-spring/issues/43)) |
| Connection cap / admission control | `none` | [#45](https://github.com/azholdaspaev/netty-loom-spring/issues/45), [#47](https://github.com/azholdaspaev/netty-loom-spring/issues/47) |
| TLS | `fails startup` | [#16](https://github.com/azholdaspaev/netty-loom-spring/issues/16) |
| HTTP/2 | `ignored` | `server.http2.*` has no effect ([#23](https://github.com/azholdaspaev/netty-loom-spring/issues/23)) |
| Response compression | `ignored` | `server.compression.*` has no effect ([#22](https://github.com/azholdaspaev/netty-loom-spring/issues/22)) |
| Forwarded headers | `partial` | `server.forward-headers-strategy=framework` applies Boot's `ForwardedHeaderFilter`; `native` does nothing, and neither fixes the session cookie's `Secure` flag ([#50](https://github.com/azholdaspaev/netty-loom-spring/issues/50)) |
| Access logging, metrics, tracing | `none` | [#8](https://github.com/azholdaspaev/netty-loom-spring/issues/8), [#6](https://github.com/azholdaspaev/netty-loom-spring/issues/6), [#7](https://github.com/azholdaspaev/netty-loom-spring/issues/7) |
