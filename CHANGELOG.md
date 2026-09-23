# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0.0 the public API may change
in any minor release.

## [Unreleased]

### Fixed

- `HttpServletResponse.getWriter()` after `getOutputStream()`, and the reverse, now throw
  `IllegalStateException` as the Servlet spec requires, instead of handing out two sinks whose
  bytes reached the body in flush order (#118).
- A listening socket that fails to close no longer aborts shutdown: the drain still begins and the
  event loops still stop, so Boot's graceful shutdown completes instead of waiting out
  `spring.lifecycle.timeout-per-shutdown-phase` (#399).

## [0.1.0] — 2026-09-20

First release, on Maven Central as `io.github.azholdaspaev:netty-loom-spring-boot-starter:0.1.0`.

### Added

- A Spring Boot 4 starter that replaces embedded Tomcat with a Netty HTTP/1.1 server and runs
  every request on a Java 25 virtual thread, on stable JDK features only. The starter's servlet
  bridge (`netty-loom-spring-mvc`) and Netty layer (`netty-loom-spring-core`) are published
  beside it.
- The blocking Spring MVC model as it is: request mapping, `@RequestBody` and `@ResponseBody`,
  content negotiation, cookies, sessions, servlet filters and listeners registered as beans, error
  pages, and Spring Security.
- Boot's standard `server.*` settings, with Netty tuning under `server.netty.*`. Every property,
  its default, and each key that is accepted and ignored:
  [docs/configuration.md](https://github.com/azholdaspaev/netty-loom-spring/blob/v0.1.0/docs/configuration.md).
- The Servlet API surface, method by method:
  [compatibility matrix](https://github.com/azholdaspaev/netty-loom-spring/blob/v0.1.0/docs/compatibility-matrix.md).

### Known limitations

This is not a servlet container. The bridge implements the part of the Servlet API a Spring MVC
service exercises, and these are the gaps a REST service is most likely to hit:

- **No TLS** (#16), **HTTP/2** (#23), **compression** (#22) or WebSocket upgrade. TLS configured
  under `server.ssl.*` fails startup; the other two are ignored.
- **No servlet async** (#18): `SseEmitter`, `DeferredResult`, `StreamingResponseBody` and
  `Callable` return values fail.
- **No multipart** (#14): `@RequestParam MultipartFile` fails with a 400.
- **Some standard `server.*` settings are accepted and silently ignored** — no warning, no startup
  failure. The list:
  [properties that are silently ignored](https://github.com/azholdaspaev/netty-loom-spring/blob/v0.1.0/docs/configuration.md#properties-that-are-silently-ignored).
- **No measured gain below roughly 2,000 concurrent connections**, and no per-request speed
  advantage at any load. The advantage is throughput per core at high connection counts:
  [benchmarks](https://github.com/azholdaspaev/netty-loom-spring/blob/v0.1.0/README.md#benchmarks).

### Migration notes

For anyone tracking pre-release snapshots:

- **`server.netty.port` was removed** in favour of Spring Boot's standard `server.port`, and its
  default of `0` (random port) with it. A leftover key is silently ignored, not rejected
  ([why](https://github.com/azholdaspaev/netty-loom-spring/blob/v0.1.0/docs/configuration.md#servernetty)); the namespace rule is
  [ADR 0001](https://github.com/azholdaspaev/netty-loom-spring/blob/v0.1.0/docs/adr/0001-server-properties-namespace.md).
- **`SessionStoreLifecycle` was renamed to `ServletContextLifecycle`**, and the auto-configured
  bean from `sessionStoreLifecycle` to `servletContextLifecycle`: since #103 its stop phase
  destroys the servlet and the filters and fires `contextDestroyed`, not only the session store
  (#350). A user bean of the old type no longer replaces it.
- **`NettyWebServerFactory` was renamed to `NettyServletWebServerFactory` and `NettyWebServer` to
  `NettyServletWebServer`**, and the auto-configured bean from `nettyWebServerFactory` to
  `nettyServletWebServerFactory`: the factory now says which stack it serves, as Boot's
  `TomcatServletWebServerFactory` and `JettyServletWebServerFactory` do, and the server no longer
  shares its simple name with `spring-boot-reactor-netty`'s `NettyWebServer` (#374).
