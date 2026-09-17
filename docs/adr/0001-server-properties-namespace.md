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

- **#16** wanted TLS under the standard `server.ssl.*` namespace.
- **#42** wanted HTTP frame-size limits, which are Netty-specific tuning with no Boot equivalent.

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
- **Netty-only tuning → `server.netty.*`.** Knobs with no Boot equivalent stay under
  `server.netty.*` (`NettyLoomProperties`); [docs/configuration.md](../configuration.md#servernetty)
  lists them.

This reconciles #16 (SSL under `server.ssl.*`) and #42 (size limits under `server.netty.*`)
without contradiction: the split is "does Spring Boot already own this concept?"

**Removal of `server.netty.port`.** The port now comes only from `server.port`. The
`@DefaultValue("0") int port` component was removed from `NettyLoomProperties`, and its entry was
pruned from `additional-spring-configuration-metadata.json`. The old default of `0` (random port)
is gone; `server.port` follows Boot's standard default of `8080`.

## Consequences

- Removing `server.netty.port` is silent for anyone still setting it: the record binds with
  `ignoreUnknownFields = true` ([docs/configuration.md](../configuration.md#servernetty)).
- The factory inherits every `server.*` setter Boot models, honoured or not. Which are honoured,
  which fail startup and which are silent no-ops is owned by
  [docs/configuration.md](../configuration.md#properties-that-are-silently-ignored). One consequence
  is a rule of this record: `server.ssl.*` with SSL enabled fails startup rather than serving
  plaintext, because an application that looks TLS-configured and is not is a security footgun.
- An out-of-context URI is answered with a bare 404 by the dispatcher, before the filter chain, so
  Spring Security and Boot's `/error` never see it. Letting it through would throw in Boot's
  `RequestPath.parse` and surface as a 500.

## Amendments

### 2026-08-16 — sessions are implemented (#159)

The ownership rule is unchanged; its worked example had drifted. #13 closed on 2026-07-29, so
`server.servlet.session.*` is no longer an inherited setter with no effect, and SSL is no longer
the only knob that fails fast. **Consequences** was corrected in place.

### 2026-09-17 — behaviour moved out (#355)

**Consequences** had grown into a per-property census that #159 had already caught drifting once.
It is cut to the rule's own consequences; the property lists live in `docs/configuration.md`, and
the **Scope** section, which listed #49's deliverables, is gone with them.
