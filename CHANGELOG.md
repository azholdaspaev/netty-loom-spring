# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0.0 the public API may change
in any minor release.

## [0.1.0] — unreleased

First release. Not yet published to Maven Central; a `0.1.0-SNAPSHOT` is on
[Central Snapshots](https://central.sonatype.com/repository/maven-snapshots/).

### Added

The list is written from [What works, what doesn't](README.md#what-works-what-doesnt) and the
[compatibility matrix](docs/compatibility-matrix.md) when the release is tagged
([docs/publishing.md](docs/publishing.md#before-tagging)); until then those two are the feature
list, and this section does not keep a third copy of it.

### Migration notes

For anyone tracking pre-release snapshots:

- **`server.netty.port` was removed** in favour of Spring Boot's standard `server.port`, and its
  default of `0` (random port) with it. A leftover key is silently ignored, not rejected
  ([why](docs/configuration.md#servernetty)); the namespace rule is
  [ADR 0001](docs/adr/0001-server-properties-namespace.md).
- **`SessionStoreLifecycle` was renamed to `ServletContextLifecycle`**, and the auto-configured
  bean from `sessionStoreLifecycle` to `servletContextLifecycle`: since #103 its stop phase
  destroys the servlet and the filters and fires `contextDestroyed`, not only the session store
  (#350). A user bean of the old type no longer replaces it.
- **The `nettyServerChannelInitializer` bean was removed** (#167): `NettyWebServerFactory` now
  builds its `NettyServerChannelInitializer` in `getWebServer()`, where `server.server-header`
  has been bound, and takes the `NettyPipelineDefinition` bean instead. A user bean of that type
  is no longer consulted; customise the pipeline through `NettyPipelineDefinition`.
