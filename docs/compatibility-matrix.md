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
| `warns` | Accepted and has no effect, but logs a WARN saying so |
| `fails startup` | Rejected loudly at startup with a message naming the property |
| `untested` | No coverage either way — not a claim about behaviour |

---

## Programming model

| Feature | Status | Notes |
| --- | --- | --- |
| `@RestController` / `@Controller`, request mapping | `works` | |
| `@RequestBody` / `@ResponseBody`, message converters | `works` | |
| Content negotiation, `@RequestParam`, `@PathVariable` | `works` | |
| `HandlerInterceptor`, `@ControllerAdvice` | `works` | |
| Spring Security | `works` | It is a servlet `Filter` with its own `SecurityContext`; it does not depend on the container auth methods, which are stubs |
| Actuator | `untested` | No compatibility test module yet ([#25](https://github.com/azholdaspaev/netty-loom-spring/issues/25)) |
| `@Async` MVC, `Callable` return values | `none` | Routes through `startAsync()` |
| Welcome page (`index.html`) | `none` | Still 500 with a static `index.html` present ([#59](https://github.com/azholdaspaev/netty-loom-spring/issues/59)), 404 without one — but no longer for want of a `RequestDispatcher`. The forward now runs and `ResourceHttpRequestHandler` finds the resource; it then calls `ServletContext.getMimeType`, which throws |

## Errors

| Feature | Status | Notes |
| --- | --- | --- |
| `sendError(int)` | `works` | Sets the status, clears the body and drops any handler-set `Content-Length`, then hands the response to the registered error page |
| `sendError(int, String)` | `works` | As the single-argument form, and the message reaches the error page as `jakarta.servlet.error.message` |
| Error-page dispatch to `/error` | `partial` | `getErrorPages()` is read at startup and a failed request is re-dispatched to the matching page with `DispatcherType.ERROR`, so `BasicErrorController` renders Spring-generated 4xx/5xx as it does on Tomcat. Global, status-specific and exception-specific registrations all match, exception pages by walking the class hierarchy. The dispatch runs as a `GET` whatever the original method was, which survives as `jakarta.servlet.error.method`. Gaps below |
| `jakarta.servlet.error.servlet_name` | `none` | Never set: this container registers one servlet and keeps no name registry. Nothing in Boot's error handling reads it |
| Error page for a response already on the wire | `none` | Declined with a warning. Tomcat falls back to `include()` on the partial response, which the `RequestDispatcher` row below shows this container does not implement |
| Error page for an `Error` rather than an `Exception` | `none` | Only `Exception` is caught; an `Error` reaches the connection and is answered exactly as the uncaught-exception row below has it, on the view that a JVM in that state should not be rendering pages |
| Error page for an out-of-context URI | `none` | The bare pre-filter 404 below never enters the context, so no page of that context answers it |
| `spring.web.error.*` | `works` | `path` selects the page Boot registers; `include-message`, `include-stacktrace`, `include-binding-errors` and `whitelabel.enabled` are Boot's own and now reach the client |
| Uncaught controller exception | `works` | Re-dispatched to the error page with the status the [exception mapping](configuration.md#exception-to-status-mapping) gives the failure as thrown, so a controller's arrives wrapped in `ServletException` as 500 and a filter's unwrapped as whatever its type maps to. Any status the handler had already set is overridden, as on Tomcat. With no page registered it falls back to a plain-text `Internal Server Error` with `Connection: close`; once bytes have been streamed the connection is closed with no response |
| Out-of-context URI (with a context path set) | `partial` | A bare 404 returned before the filter chain — no filter, listener or Security rule sees it. Deliberate; see [ADR 0001](adr/0001-server-properties-namespace.md) |

## Request

| Feature | Status | Notes |
| --- | --- | --- |
| `getRemoteAddr/Host/Port`, `getLocalAddr/Name/Port` | `works` | `getRemoteHost` returns the IP — never a reverse-DNS name. `getLocalName` returns an IP, not a hostname. An unresolvable `SocketAddress` degrades to `""` / `0` |
| `getScheme()`, `isSecure()` | `partial` | Derived from the presence of an `SslHandler`, so in practice always `http` / `false` — TLS is unimplemented and forwarded headers are not consulted |
| `getServerName()`, `getServerPort()` | `works` | Parsed from the `Host` header, IPv6 bracketed; falls back to the local address when `Host` is absent |
| `getHeader*`, `getHeaderNames` | `works` | `getDateHeader` throws `IllegalArgumentException` on an unparseable date. From a controller that surfaces as **500**, because `FrameworkServlet` wraps it in a `ServletException` ([exception mapping](configuration.md#exception-to-status-mapping)); from a `Filter`, which is above `DispatcherServlet`, it arrives unwrapped and maps to `400` |
| `getCookies()` | `partial` | `ServerCookieDecoder.STRICT`. Returns `null`, not an empty array, when there are none or the header is malformed |
| `getParameter*` | `partial` | Query and form-body parameters merged, query first. **Netty's `QueryStringDecoder` limits apply: at most 1024 parameters, the rest silently dropped** ([#122](https://github.com/azholdaspaev/netty-loom-spring/issues/122)); `;` is a parameter separator; `+` decodes to a space |
| `getParameterMap()` | `partial` | Unmodifiable map, but the `String[]` values are live ([#120](https://github.com/azholdaspaev/netty-loom-spring/issues/120)) |
| `getInputStream()` / `getReader()` | `partial` | Over the body as it arrives, blocking the request's virtual thread; mutually exclusive, as the spec requires. `getReader()` defaults to **UTF-8**, where the spec says ISO-8859-1. The stream is consuming, so the body cannot be re-read. `setReadListener` throws — async reads are [#18](https://github.com/azholdaspaev/netty-loom-spring/issues/18) |
| Body consumption by `getParameter` | `works` | Reading form parameters consumes the body, as on Tomcat — unless `getInputStream()` or `getReader()` has already claimed it, which stops the parse exactly as Tomcat's `Request.doParseParameters` does. The form is bounded by the request-body cap alone; there is no separate `maxPostSize` |
| `setCharacterEncoding` | `partial` | Silently ignored once parameters are parsed or `getReader()` has been called |
| Query-string charset | `partial` | Always decoded as UTF-8, independent of the request encoding |
| Request body size | `partial` | Streamed, never held whole: the connection stops asking for more once 64 KiB is undrained — a threshold checked per read loop, not a ceiling on resident bytes — and the total is capped at 1 MiB, answered `413` ([#42](https://github.com/azholdaspaev/netty-loom-spring/issues/42) makes the cap a property) |
| `getLocale*` | `works` | Full `Accept-Language` q-value parsing |
| `getRequestDispatcher(path)` | `partial` | Path-based `forward` only. A `/`-prefixed path is context-relative, anything else resolves against the current request's directory; the result is canonicalised with `StringUtils.cleanPath`, which also turns `\` into `/`. `null` comes back for a `null` path, one that canonicalises out of the context, or one that cannot be percent-decoded. The escape decision strips `;` parameters and percent-decodes first, so `/..;/x` and `/%2e%2e/x` are rejected too; the path actually dispatched stays undecoded, as `getRequestURI()` requires |
| `getPathInfo()`, `getPathTranslated()` | `none` | Always `null`; `getServletPath()` returns the whole context-relative path |
| `getParts()`, `getPart(name)` | `none` | Always empty / `null` ([#14](https://github.com/azholdaspaev/netty-loom-spring/issues/14)) |
| `startAsync()` | `none` | Returns **`null`** where the spec requires `IllegalStateException` ([#18](https://github.com/azholdaspaev/netty-loom-spring/issues/18)) |
| `upgrade(Class)` | `none` | Returns `null` — no WebSocket, no HTTP upgrade |
| `getUserPrincipal`, `getRemoteUser`, `getAuthType` | `none` | Always `null` |
| `isUserInRole` | `none` | Always `false` |
| `authenticate`, `login`, `logout` | `none` | Silent no-ops — `login` reports no failure, so a caller believes it succeeded |
| `getRequestId()` | `none` | Always `""`, so every request shares one id where the spec requires a unique one ([#116](https://github.com/azholdaspaev/netty-loom-spring/issues/116)) |
| `getProtocolRequestId()` | `works` | `""`, which is what the spec prescribes for HTTP/1.x |
| `getServletConnection()` | `none` | Returns **`null`**, for which the spec defines no case, so callers NPE at their own call site ([#116](https://github.com/azholdaspaev/netty-loom-spring/issues/116)) |
| `getDispatcherType()` | `partial` | `REQUEST` for the initial dispatch, `FORWARD` during a forward and `ERROR` during an error-page dispatch. `INCLUDE` and `ASYNC` are unreachable |

## Response

| Feature | Status | Notes |
| --- | --- | --- |
| `getOutputStream()` / `getWriter()` | `partial` | Streams as `Transfer-Encoding: chunked` once the body outgrows the 8 KB buffer or `flushBuffer()` is called; otherwise a single `Content-Length` response. Both can currently be obtained on the same response ([#118](https://github.com/azholdaspaev/netty-loom-spring/issues/118)) |
| Framing | `partial` | Matches Tomcat for the body-carrying cases. On a `304` this server sends `Content-Length: 0` where Tomcat sends none; see [docs/configuration.md](configuration.md#response-framing) for the full table |
| Backpressure | `works` | A handler producing faster than the client reads blocks on its virtual thread, bounded by the write-stall timeout (`server.netty.write-stall-timeout`, 60s by default) |
| `setBufferSize`, `getBufferSize` | `works` | Default 8192; `setBufferSize` throws once anything is written |
| `flushBuffer()` | `works` | Commits, as the spec requires: the head goes on the wire, so `setStatus` and the header mutators no-op afterwards and `resetBuffer` throws |
| `reset()`, `resetBuffer()` | `partial` | Both throw once the head is on the wire. `resetBuffer` drops a still-bound `PrintWriter` ([#117](https://github.com/azholdaspaev/netty-loom-spring/issues/117)), and drops `Content-Length` too, where the spec has it clear the buffer *without* clearing headers: an emptied body must not ship behind a length describing what was discarded, and this container recomputes the header whenever it is absent |
| Headers, `setStatus` | `works` | Silently no-op after commit, as the spec requires |
| `addCookie` | `partial` | Maps name, value, path, domain, max-age, `Secure`, `HttpOnly`, `SameSite`, `Partitioned`. **`Expires`, `Comment` and `Version` are silently dropped** |
| `CookieSameSiteSupplier` beans | `partial` | An explicit cookie attribute wins over any supplier. Deviates from Tomcat at `server.servlet.session.cookie.same-site=omitted`: Tomcat treats `omitted` as an opinion that suppresses the supplier for the session cookie, where here no attribute is written and a matching supplier then applies to `JSESSIONID` |
| `sendRedirect` | `partial` | Sets `Location` verbatim — no relative-to-absolute resolution, and the buffered body is **not** discarded, unlike `sendError` |
| `encodeURL`, `encodeRedirectURL` | `none` | Identity functions — no URL session rewriting |
| `setLocale` | `ignored` | No-op; `getLocale()` returns the JVM default, so `Content-Language` is never emitted |
| `Date` and `Server` response headers | `none` | Neither is ever emitted. Tomcat always sends `Date` |
| `setCharacterEncoding(null)` | `partial` | Throws `IllegalArgumentException`; Servlet 6.1 says it should reset to the default |
| `setWriteListener` | `ignored` | No-op, so `onWritePossible()` never fires and a `WriteListener`-based writer stalls rather than failing fast |
| `setReadListener` | `none` | Throws `UnsupportedOperationException` |

## Request dispatch

Path-based `forward` only ([#182](https://github.com/azholdaspaev/netty-loom-spring/issues/182)).

| Feature | Status | Notes |
| --- | --- | --- |
| `forward(request, response)` | `partial` | Re-enters the filter chain and `DispatcherServlet` with the target path. Throws `IllegalStateException` once the response is committed, and clears an uncommitted buffer first. No dispatch-loop guard, so a servlet forwarding to its own path recurses until the stack runs out — as it does on Tomcat |
| Committing the response on return | `none` | The spec has the container commit and close the response before `forward` returns; here it stays buffered so the reply keeps its `Content-Length` instead of turning into a chunked one. The cost is that output written *after* `forward` returns is appended rather than discarded |
| `include(request, response)` | `none` | Throws `UnsupportedOperationException` → **501**. Note that Spring's `forward:` view checks `isCommitted()` first and takes the *include* branch on a committed response, so that case 501s rather than reporting the commit |
| Named dispatch (`getNamedDispatcher`) | `none` | Throws. `addServlet` keeps only the class name and `DispatcherServlet` is never registered, so there is nothing to resolve a name against |
| `jakarta.servlet.forward.*` attributes | `partial` | `request_uri`, `context_path`, `servlet_path` and `query_string` (the last only when the original request carried one). `forward.path_info` is never set, since `getPathInfo()` is always `null`; `forward.mapping` is never set, since `HttpServletMapping` is unimplemented. A nested forward reports the values of the request the client sent, not the previous hop |
| Forwarded parameters | `works` | A query string on the dispatch path takes precedence over the original's and is added to it, for the duration of the forward only. Decoded as UTF-8, like every other query string here |
| `ServletRequestListener` on a forward | `works` | Fires once per request, not once per dispatch |

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

Registered through `ServletContext.addListener`, so `ServletListenerRegistrationBean` and
`HttpSessionEventPublisher` both work — each is a `ServletContextInitializer` that calls it.

**`@WebListener` does not.** Under `@ServletComponentScan` Boot collects the annotated class names
into `ServletWebServerSettings.webListenerClassNames`, which `NettyWebServerFactory` never reads, so
they are dropped without reaching `addListener` and without a warning.

| Listener | Status |
| --- | --- |
| `ServletContextListener` | `works` |
| `ServletContextAttributeListener` | `works` |
| `ServletRequestListener` | `works` |
| `ServletRequestAttributeListener` | `works` |
| `HttpSessionListener` | `works` |
| `HttpSessionAttributeListener` | `works` |
| `HttpSessionIdListener` | `works` |

Those seven are exactly what `addListener` accepts (`NettyListenerRegistry.SUPPORTED_TYPES`);
anything else is rejected with `IllegalArgumentException`, as on Tomcat. `AsyncListener` is
registered through `AsyncContext`, which does not exist here.

Two further interfaces are attribute-value contracts rather than registration types — they are
fired on an object bound into a session, and passing one to `addListener` throws:

| Interface | Status | Notes |
| --- | --- | --- |
| `HttpSessionBindingListener` | `works` | `valueBound` and `valueUnbound` both fire, for a value bound as a session attribute |
| `HttpSessionActivationListener` | `none` | Sessions are never passivated or activated, so `sessionDidActivate` never fires, silently |

## Filters and servlets

| Feature | Status | Notes |
| --- | --- | --- |
| `addFilter(name, Filter instance)` | `works` | This is what `FilterRegistrationBean` and Boot's own filters use |
| `addFilter(name, Class)` / `addFilter(name, String className)` | `ignored` | Registration is recorded and **silently skipped at request time** — the filter is never instantiated and never runs |
| Filter URL-pattern mappings | `works` | Servlet-spec §12.2 matching (exact, `/*`, `/prefix/*`, `*.ext`) against the context-relative path |
| Filter servlet-name mappings | `warns` | Logged at WARN and discarded — unlike the `Class` overload above, which is silent |
| Filter dispatcher types | `partial` | `FORWARD` and `ERROR` mappings match, against the **target** path of the dispatch; `INCLUDE` / `ASYNC` remain unreachable. Note that Boot registers every `OncePerRequestFilter` for all dispatcher types, so its own filters (`CharacterEncodingFilter`, `FormContentFilter`, `RequestContextFilter`, `ServerHttpObservationFilter`) are re-entered and skip themselves — on a forward via their already-filtered request attribute, on an error dispatch via `jakarta.servlet.error.request_uri`, which is why that attribute is set before the dispatch runs. Spring Security's chain is registered for `ERROR` and does re-run, as on Tomcat |
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
| `getRequestDispatcher` | `partial` | Same resolution as the request method above, except that the path must be context-absolute: a relative one returns `null` |
| `getMimeType`, `getNamedDispatcher`, `getContext`, `addJspFile`, `createServlet`, `createFilter`, `getJspConfigDescriptor`, `declareRoles`, `getVirtualServerName`, `get/setRequestCharacterEncoding`, `get/setResponseCharacterEncoding` | `none` | Throw `UnsupportedOperationException`, which surfaces as **501** if it reaches the pipeline unwrapped, or 500 once Spring wraps it |
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
| Bounded outbound queue | `none` | A pipelining client that never reads accumulates unbounded buffer — 5.7 MB queued out for 888 bytes in, measured ([#88](https://github.com/azholdaspaev/netty-loom-spring/issues/88)). The write-stall timeout above bounds this in time, not in space |
| Handler execution deadline | `none` | A handler that never returns holds its connection until shutdown ([#43](https://github.com/azholdaspaev/netty-loom-spring/issues/43)) |
| Connection cap / admission control | `none` | [#45](https://github.com/azholdaspaev/netty-loom-spring/issues/45), [#47](https://github.com/azholdaspaev/netty-loom-spring/issues/47) |
| TLS | `fails startup` | [#16](https://github.com/azholdaspaev/netty-loom-spring/issues/16) |
| HTTP/2 | `ignored` | `server.http2.*` has no effect ([#23](https://github.com/azholdaspaev/netty-loom-spring/issues/23)) |
| Response compression | `ignored` | `server.compression.*` has no effect ([#22](https://github.com/azholdaspaev/netty-loom-spring/issues/22)) |
| Forwarded headers | `partial` | `server.forward-headers-strategy=framework` applies Boot's `ForwardedHeaderFilter`; `native` does nothing, and neither fixes the session cookie's `Secure` flag ([#50](https://github.com/azholdaspaev/netty-loom-spring/issues/50)) |
| Access logging, metrics, tracing | `none` | [#8](https://github.com/azholdaspaev/netty-loom-spring/issues/8), [#6](https://github.com/azholdaspaev/netty-loom-spring/issues/6), [#7](https://github.com/azholdaspaev/netty-loom-spring/issues/7) |
