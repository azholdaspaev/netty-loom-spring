# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew build                              # Full build (compile + test)
./gradlew build -x test                      # Build without tests
./gradlew :netty-loom-spring-core:test       # Test a single module
./gradlew test --tests 'io.azholdaspaev.nettyloom.autoconfigure.smoke.test.SmokeControllerTest'  # Single test
```

All Java compilation and execution requires `--enable-preview` (configured in root build.gradle.kts).

## Architecture

This is a Spring Boot integration library that replaces Tomcat/Jetty with a Netty-based web server using Java virtual threads (Project Loom).

**Modules (flat layout, all included in settings.gradle.kts):**

- **netty-loom-spring-core** — Pure Netty layer, no Spring dependency. `NettyServer` manages lifecycle; `NettyServerChannelInitializer` delegates to `NettyPipelineConfigurer` SPI for channel pipeline setup. Netty transport, codec-http, handler, plus native epoll/kqueue transports.
- **netty-loom-spring-mvc** — Spring MVC servlet bridge. Contains `NettyServletContext` interface (Jakarta ServletContext with default UnsupportedOperationException stubs) and `DefaultNettyServletContext` implementation that allows Spring MVC to run on top of Netty.
- **netty-loom-spring-boot-starter** — Spring Boot auto-configuration entry point. `NettyWebServerFactory` implements `ServletWebServerFactory` SPI; `NettyWebServer` implements `WebServer`. Auto-config registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- **netty-loom-spring-example-tomcat** / **netty-loom-spring-example-netty** — Example apps (placeholders).

**Package root:** `io.azholdaspaev.nettyloom`

Sub-packages: `core.server`, `core.pipeline`, `mvc.servlet`, `autoconfigure`, `autoconfigure.server`

**Dependency flow:** `starter → mvc → core` (core has no Spring dependency)

## Build System Details

- **Gradle 9.4.1**, Kotlin DSL, Java 25 toolchain
- **Spring Boot BOM 4.0.5** imported via `io.spring.dependency-management` plugin — Spring/Jakarta deps need no explicit version
- **Version catalog** at `gradle/libs.versions.toml` for Netty 4.2.12.Final, JUnit 6.0.3, etc.
- Native transport deps use classifier variants: `variantOf(libs.netty.transport.native.epoll) { classifier("linux-x86_64") }`
- `the<DependencyManagementExtension>()` doesn't work inside `subprojects {}` — use `pluginManager.withPlugin("io.spring.dependency-management") { configure<...> {} }` instead
- `libs` version catalog accessor is not available inside `subprojects {}` blocks — extract needed values to `val` at root level first

## Development Workflow

All source code changes must strictly follow TDD (Test-Driven Development): write a failing test first, then write the minimal production code to make it pass, then refactor. Never write production code without a corresponding test already in place.

Tests use JUnit 6 (`org.junit.gen6`) on JUnit Platform. All test tasks are configured with `useJUnitPlatform()` and require `--enable-preview`.

## CI

GitHub Actions (`.github/workflows/build.yml`): builds on push to main and PRs, uses Temurin JDK 25.
