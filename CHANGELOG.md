# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0.0 the public API may change
in any minor release.

## [0.1.0] — unreleased

First release. Not yet published to Maven Central; publishing is tracked by
[#31](https://github.com/azholdaspaev/netty-loom-spring/issues/31).

### Added

- **Netty-backed `ServletWebServerFactory`** that replaces the embedded Tomcat container in a
  Spring Boot application, dispatching each request onto its own Java 25 virtual thread. Blocking
  `@RestController` code runs unchanged.
- **Transport auto-selection** via `server.netty.transport` — epoll on Linux, kqueue on macOS,
  NIO everywhere else; `nio`, `epoll` and `kqueue` force a specific transport, the two native
  values failing startup when unavailable.
- **Two-phase graceful shutdown** with a configurable drain deadline
  (`server.netty.shutdown-grace-period`). Idle keep-alive connections are closed rather than waited
  on; in-flight requests are drained, then force-closed at the deadline.
- **Slow-loris protection** through a per-connection read timeout (`server.netty.read-timeout`)
  that measures client progress and exempts handler execution.
- **Fixed HTTP frame-size limits** answering `414`, `431` and `413` rather than passing malformed
  or oversized requests to the application.
- **Streaming responses** — writes flush incrementally as `Transfer-Encoding: chunked` once the
  body outgrows the response buffer, with backpressure when the client reads more slowly than the
  handler writes.
- **Servlet bridge** covering requests, responses, cookies, sessions, instance-registered filters
  and all seven `addListener` listener types, over `NettyHttpServletRequest`,
  `NettyHttpServletResponse` and `DefaultNettyServletContext`.
- **In-memory sessions** with the standard `server.servlet.session.*` cookie and timeout
  properties, plus `CookieSameSiteSupplier` support.
- **Three SPI seams for extension** — `NettyPipelineConfigurer` for the channel pipeline,
  `HttpRequestDispatcher` for the layer above it, and `HttpResponseWriter` for how that layer emits
  a response, keeping `netty-loom-spring-core` free of any Spring dependency.
- **Configuration metadata** for every `server.netty.*` property, with IDE value hints.

### Known limitations

This release is deliberately partial, and several standard Spring Boot settings are accepted and
then silently ignored. Read [What works, what doesn't](README.md#what-works-what-doesnt) and the
[compatibility matrix](docs/compatibility-matrix.md) before adopting it. The largest gaps: no
`/error` page dispatch, no servlet async (so no SSE or `StreamingResponseBody`), no multipart, no
static resources, no TLS, no HTTP/2 and no response compression.

### Migration notes

For anyone tracking pre-release snapshots:

- **`server.netty.port` was removed** in favour of Spring Boot's standard `server.port`. Because
  `NettyLoomProperties` is a `@ConfigurationProperties` record, it binds with
  `ignoreUnknownFields = true`, so a leftover `server.netty.port` is **silently ignored rather than
  rejected** — an application that forgets to rename the key gets Boot's default of `8080` with no
  warning. The old property's default of `0` (random port) is gone. See
  [ADR 0001](docs/adr/0001-server-properties-namespace.md).
