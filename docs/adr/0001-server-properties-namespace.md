# ADR 0001 — `server.*` vs `server.netty.*` configuration namespace

- Status: Accepted
- Date: 2026-07-03
- Amended: 2026-08-16 ([#159](https://github.com/azholdaspaev/netty-loom-spring/issues/159))
- Issue: [#49](https://github.com/azholdaspaev/netty-loom-spring/issues/49)

## Context

The library's value proposition is a drop-in Tomcat replacement. Before #49 it ignored Spring
Boot's standard `server.*` namespace entirely: `NettyWebServerFactory` implemented the bare
`ServletWebServerFactory`, so Boot's `ServerProperties` were never bound, and all configuration
lived under a parallel `@ConfigurationProperties("server.netty")`. As a result `server.port=8080`
silently bound a **random** port (only `server.netty.port`, default `0`, was honored), and
`server.address` / `server.servlet.context-path` had no effect at all.

Two adjacent issues forced a decision on where configuration should live:

- **#16** wants TLS under the standard `server.ssl.*` namespace.
- **#42** wants HTTP frame-size limits, which are Netty-specific tuning with no Boot equivalent.

Without a rule, contributors would guess, and the two namespaces would drift into an inconsistent
mix.

## Decision

**Ownership rule:**

- **Standard knobs → `server.*`.** Anything Spring Boot already models — port, address,
  context-path, SSL (`server.ssl.*`, #16), error handling — binds through Boot's
  `ServerProperties` and `ServletWebServerFactoryCustomizer`. `NettyWebServerFactory` now extends
  `AbstractConfigurableWebServerFactory` and implements `ConfigurableServletWebServerFactory`, so
  these are pushed onto the factory before `getWebServer()` runs, exactly as the Tomcat/Jetty
  factories work.
- **Netty-only tuning → `server.netty.*`.** Knobs with no Boot equivalent — boss/worker thread
  counts, TCP keep-alive, graceful-shutdown grace period, read-timeout, transport selection, and
  the future frame-size limits (#42) — stay under `server.netty.*` (`NettyLoomProperties`).

This reconciles #16 (SSL under `server.ssl.*`) and #42 (size limits under `server.netty.*`)
without contradiction: the split is "does Spring Boot already own this concept?"

**Removal of `server.netty.port`.** The port now comes only from `server.port`. The
`@DefaultValue("0") int port` component was removed from `NettyLoomProperties`, and its entry was
pruned from `additional-spring-configuration-metadata.json`. The old default of `0` (random port)
is gone; `server.port` follows Boot's standard default of `8080`.

## Consequences

### Silent-ignore caveat

`NettyLoomProperties` is a `@ConfigurationProperties` record, which binds with
`ignoreUnknownFields = true` (the default). A stray `server.netty.port=...` left over from an
earlier version is therefore **silently ignored — not a startup error**. Users migrating from
`server.netty.port` to `server.port` who forget to change the key will get Boot's default `8080`
with no warning. This is documented here and in the CHANGELOG.

### Some inherited setters fail fast; the rest are silent no-ops

Because the factory now extends `AbstractConfigurableWebServerFactory` and implements
`ConfigurableServletWebServerFactory`, it **inherits** setters for capabilities the Netty server
does not yet implement.

`server.ssl.*` is treated specially: since silently serving plaintext while the application looks
TLS-configured is a security footgun, `getWebServer()` **fails fast** with a clear
`WebServerException` pointing at #16 whenever SSL is enabled. Wire TLS (#16) or set
`server.ssl.enabled=false`.

`setSession` is split three ways: `server.servlet.session.timeout`, `cookie.*` and
`tracking-modes` are honoured (#13); `persistent=true` and any tracking mode other than `cookie`
**fail startup**; only `store-dir` is read by nothing.

The remaining inherited setters are silent no-ops by design (the interface contract requires them);
these `server.*` knobs appear configurable but currently have **no effect**:

- `setHttp2` (#23), `setCompression` (#22), `setServerHeader` — not applied to the Netty pipeline.
- `setMimeMappings` — no static resource serving.

[docs/configuration.md](../configuration.md#properties-that-are-silently-ignored) owns the
per-property list and is the one to keep current.

### Bypassed error handling for out-of-context requests

The context-path 404 is returned directly by the dispatcher, before the filter chain, so Spring
Security's filter and Boot's `BasicErrorController` `/error` JSON are bypassed for out-of-context
URIs — a plain 404, by design (an out-of-context URI would otherwise throw in Boot's
`RequestPath.parse` and surface as a 500).

## Scope

In scope for #49: `server.port`, `server.address`, `server.servlet.context-path`. Session timeout
(`server.servlet.session.timeout`) is deferred to #13.

## Amendments

### 2026-08-16 — sessions are implemented (#159)

The ownership rule is unchanged; its worked example had drifted. #13 closed on 2026-07-29, so
`server.servlet.session.*` is no longer an inherited setter with no effect, and SSL is no longer
the only knob that fails fast. **Consequences** was corrected in place.

**Scope** above still describes #49's boundary as it stood on 2026-07-03 and is left as written.
