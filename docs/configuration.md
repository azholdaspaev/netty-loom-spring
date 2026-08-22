# Configuration reference

Every property this library reads, everything it rejects, everything it ignores, and the two
behaviours whose design you need to understand before tuning them: response framing and the read
timeout.

The rule for which namespace a knob belongs to — Spring Boot's `server.*` versus Netty-only
`server.netty.*` — is [ADR 0001](adr/0001-server-properties-namespace.md).

---

## `server.netty.*`

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `server.netty.transport` | `NettyTransportPreference` | `auto` | `auto` picks the best native transport (epoll on Linux, kqueue on macOS) and falls back to NIO when neither is available. Only the `linux-*` epoll and `osx-*` kqueue natives are bundled, so on any other BSD `auto` falls back to NIO. `nio` forces the portable transport. `epoll` and `kqueue` force that transport and **fail startup** if it is unavailable. Any other value fails startup when the property binds; an unset or empty value falls back to `auto`. The selected transport is logged at INFO on startup |
| `server.netty.boss-threads` | `int` | `1` | Threads in the boss event-loop group, which accepts connections. One is normally enough |
| `server.netty.worker-threads` | `int` | `0` | Threads in the worker event-loop group. `0` applies Netty's default of `2 × availableProcessors()` |
| `server.netty.tcp-keep-alive` | `boolean` | `true` | Socket-level `SO_KEEPALIVE` on accepted channels. Unrelated to HTTP keep-alive, which is protocol behaviour and always on |
| `server.netty.shutdown-grace-period` | `Duration` | `30s` | How long graceful shutdown waits for in-flight requests before force-closing. See [Graceful shutdown](#graceful-shutdown) |
| `server.netty.read-timeout` | `Duration` | `30s` | Client-progress deadline. See [The read timeout](#the-read-timeout). `0` or negative disables it |

`NettyLoomProperties` is a `@ConfigurationProperties` record, which binds with
`ignoreUnknownFields = true`. A misspelled or obsolete key under `server.netty.*` is therefore
**silently dropped, not a startup error** — most importantly `server.netty.port`, which was removed
in favour of `server.port`.

## Standard `server.*` properties that are honoured

| Property | Notes |
| --- | --- |
| `server.port` | Default 8080; `0` lets the OS choose. A bind failure is reported as Boot's `PortInUseException` where the OS says the port is in use |
| `server.address` | Unset binds every interface |
| `server.servlet.context-path` | Applied before initializers run, so `getContextPath()` is correct during `onStartup`. Also the default session-cookie path |
| `server.servlet.session.timeout` | Second resolution; `0` or less means sessions never expire |
| `server.servlet.session.cookie.*` | `name`, `domain`, `path`, `http-only`, `secure`, `max-age`, `same-site`, `partitioned` |
| `server.servlet.session.tracking-modes` | Only `cookie`; an empty set is legal and disables the cookie |
| `server.servlet.context-parameters.*` | Become `ServletContext` init parameters |
| `spring.servlet.encoding.*` | Works because Boot implements it as a `CharacterEncodingFilter` bean, not a container setting |

Two extension points also work: `WebServerFactoryCustomizer<ConfigurableServletWebServerFactory>`
beans and `CookieSameSiteSupplier` beans.

## Properties that fail startup

| Property | Message |
| --- | --- |
| `server.ssl.*` with SSL enabled | `WebServerException` naming [#16](https://github.com/azholdaspaev/netty-loom-spring/issues/16); remove `server.ssl.*` or set `server.ssl.enabled=false`. Note `Ssl.enabled` defaults to `true`, so binding **any** `server.ssl.*` key — `server.ssl.bundle` included — is enough to trip this. Failing loudly is deliberate — silently serving plaintext while the app looks TLS-configured is a security footgun |
| `server.servlet.session.persistent=true` | `WebServerException`; sessions are in-memory only. Use Spring Session for a durable store |
| `server.servlet.session.tracking-modes` with `URL` or `SSL` | `IllegalArgumentException` naming the property |
| `server.netty.transport` unknown or unavailable | A binding failure naming the property, with Boot's failure analyzer listing the valid constants (`AUTO`, `EPOLL`, `KQUEUE`, `NIO`) at startup, or `IllegalStateException` if the requested native transport is not available on this platform |

## Properties that are silently ignored

`NettyWebServerFactory` extends Boot's `AbstractConfigurableWebServerFactory`, so Boot binds and
pushes the entire `server.*` surface onto it. The factory reads only the properties listed above.
Everything below is set on the factory and never read again — **no warning, no startup failure**.

| Property | Why it does nothing | Issue |
| --- | --- | --- |
| `server.compression.*` | No `HttpContentCompressor` in the pipeline | [#22](https://github.com/azholdaspaev/netty-loom-spring/issues/22) |
| `server.http2.enabled` | `HttpServerCodec` is HTTP/1.1 only | [#23](https://github.com/azholdaspaev/netty-loom-spring/issues/23) |
| `server.shutdown=immediate` | Boot 4 registers the graceful-shutdown lifecycle for every factory; this one never reads `getShutdown()` to opt out, so it always drains | [#87](https://github.com/azholdaspaev/netty-loom-spring/issues/87) |
| `server.server-header` | Never written to a response | |
| `server.max-http-request-header-size` | Superseded by the fixed 10,000-byte header limit | [#42](https://github.com/azholdaspaev/netty-loom-spring/issues/42) |
| `spring.web.error.*` and error-page registrations | `getErrorPages()` is never read; there is no `ERROR` dispatch | [#38](https://github.com/azholdaspaev/netty-loom-spring/issues/38) |
| `server.mime-mappings.*` | Never read; `ServletContext.getMimeType` throws | |
| `server.servlet.application-display-name` | `getServletContextName()` is hardcoded | [#86](https://github.com/azholdaspaev/netty-loom-spring/issues/86) |
| `server.servlet.register-default-servlet` | Only the `DispatcherServlet` is ever initialized | |
| `server.servlet.jsp.*` | No JSP servlet | |
| `server.servlet.encoding.mapping.*` | Locale-to-charset mappings are never read. Note this is a different property from `spring.servlet.encoding.*`, which *is* honoured because Boot implements it as a filter | |
| `server.servlet.session.store-dir` | Only `persistent` is checked | |
| `spring.ssl.bundle.*` | Never read. Unlike `server.ssl.bundle`, which fails startup, this namespace does not bind to `server.ssl` and so starts cleanly | [#16](https://github.com/azholdaspaev/netty-loom-spring/issues/16) |
| `server.forward-headers-strategy=native` | No native forwarded-header handling. `=framework` does work, via Boot's `ForwardedHeaderFilter` | [#50](https://github.com/azholdaspaev/netty-loom-spring/issues/50) |
| `spring.mvc.servlet.path` | `DispatcherServlet` receives every in-context request regardless | |
| `server.netty.port` | Removed; use `server.port` | |
| `server.tomcat.*`, `server.jetty.*`, `server.undertow.*` | Those containers are not on the classpath | |

In Spring Boot 4 the error properties moved from `server.error.*` to `spring.web.error.*`. Neither
is honoured here.

## Fixed limits

Not configurable ([#42](https://github.com/azholdaspaev/netty-loom-spring/issues/42)):

| Limit | Value | Exceeded by a request → |
| --- | --- | --- |
| Max initial line | 10,000 bytes | `414`, connection closed |
| Max header block | 10,000 bytes | `431`, connection closed |
| Max chunk size | 10,000 bytes | — |
| Max aggregated body | 1 MiB | `413` |
| Listen backlog (`SO_BACKLOG`) | 128 | — |
| Write-stall timeout | 60s | Connection closed when a client stops reading mid-response |

These are 10,000 decimal bytes, not 10 KiB.

## Graceful shutdown

Shutdown runs in two phases. First the server channel closes and the connection registry begins
draining, which closes currently-idle keep-alive connections rather than waiting on them. Then, on
a dedicated non-daemon thread, in-flight requests are awaited up to
`server.netty.shutdown-grace-period`; anything still outstanding at the deadline is force-closed.
While draining, the last response owed on each connection carries `Connection: close`.

**Set `server.netty.shutdown-grace-period` strictly below
`spring.lifecycle.timeout-per-shutdown-phase`.** Both default to 30s in Spring Boot 4, so at the
defaults a slow drain is not guaranteed to complete before Spring tears the session store down
([#89](https://github.com/azholdaspaev/netty-loom-spring/issues/89)).

`server.shutdown=immediate` does not disable any of this
([#87](https://github.com/azholdaspaev/netty-loom-spring/issues/87)).

## The read timeout

`server.netty.read-timeout` is a **single interval that measures the client, not your handler**.
It is armed when the connection is accepted — which is what makes it a slow-loris defence, since a
client that connects and says nothing is closed one interval later — and restarts each time a
response is written.

The handler is exempt. The clock is suspended for as long as any request on the connection is
outstanding, so however long a controller blocks, the connection is never closed out from under it.

The design counts requests, not bytes. A byte-level clock would be refreshed by every byte, so a
client dribbling a header one byte at a time would hold its connection forever; counting requests
makes the interval a deadline for delivering a *whole* request.

The cost is that idle time and delivery share one budget. Nothing inbound advances the clock:

| Scenario (30s timeout) | Outcome |
| --- | --- |
| Slow handler, any duration | Answered — dispatch is exempt |
| Idle 5s, then a request arrives | Fine — 25s left to deliver it |
| **Idle 29s, then a large upload begins** | **Closed 1s in, mid-upload** |
| Header dribbled a byte at a time | Closed — the loris defence |

The last two are the same mechanism. If your clients pool connections and send large bodies after
long idle periods, size `read-timeout` for the sum, not for either part.

Setting it to `0` or a negative value disables reclamation entirely, leaving only shutdown to
reclaim connections.

## Response framing

Framing follows Tomcat for the body-carrying cases, which means most handlers are chunked rather
than length-declared. The bodyless statuses are where it diverges. Worth knowing before you measure
it — these are the bytes on the wire, not the headers the servlet layer set.

| Handler returns | Framing | Why |
| --- | --- | --- |
| `ResponseEntity<Pojo>` | `Transfer-Encoding: chunked` | Spring flushes after writing an entity, and Jackson cannot report a length without serialising first |
| `ResponseEntity<String>` | `Content-Length` | Same flush, but `StringHttpMessageConverter` does report a length |
| `@ResponseBody` (any type) | `Content-Length` | Never flushed, so it commits once, whole |
| Body over 8 KB, or an explicit `flushBuffer()` | `Transfer-Encoding: chunked` | Committed before the body is complete |
| `205`, `304` | `Content-Length: 0` | No body can follow. `304` deviates from Tomcat, which sends no length here: a bare head would make `HttpServerKeepAliveHandler` close after every conditional GET |
| `204`, `1xx` | No `Content-Length` | No body can follow; Netty's encoder strips the header the server set |

The `ResponseEntity` rows are Spring's doing, not this server's: `HttpEntityMethodProcessor` calls
`flush()` after writing an entity, and a flush commits. Tomcat behaves identically — verified by
running the same endpoints on `netty-loom-spring-example-tomcat`.

Responses stream. Writing to `HttpServletResponse.getOutputStream()` flushes incrementally once the
body outgrows the 8 KB response buffer (`setBufferSize`) or the handler calls `flushBuffer()`, and a
handler producing faster than the client reads is made to wait. Requests, by contrast, are buffered
whole ([#51](https://github.com/azholdaspaev/netty-loom-spring/issues/51)).

Two headers Tomcat always sends are never emitted here: `Date` and `Server`.

## Exception-to-status mapping

`HttpExceptionHandler` sits at the tail of the pipeline. `ReadTimeoutException` and client
disconnects (`ClosedChannelException`, `PrematureChannelClosureException`, and `IOException`s whose
message names a reset, broken pipe or forcible close) close the connection with **no response**.
Everything else maps to a status, is logged (`5xx` at error, below that at warn), and is answered
with a plain-text body equal to the reason phrase plus `Connection: close`.

| Cause | Status |
| --- | --- |
| `TooLongHttpHeaderException` | `431` |
| `TooLongHttpLineException` | `414` |
| `TooLongFrameException` | `413` |
| `DecoderException`, `IllegalArgumentException` | `400` |
| `UnsupportedOperationException` | `501` |
| anything else | `500` |

A `DecoderException` with a cause is unwrapped first. Note that Spring's `FrameworkServlet` wraps
application exceptions in a `ServletException`, which is not unwrapped — so an
`UnsupportedOperationException` thrown by your controller arrives as `500`, not `501`.
