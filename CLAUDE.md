# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew build                              # Full build (compile + test)
./gradlew build -x test                      # Build without tests
./gradlew :netty-loom-spring-core:test       # Test a single module
./gradlew test --tests 'fully.qualified.TestClass.methodName'  # Single test
```

All Java compilation and execution requires `--enable-preview` (configured in root build.gradle.kts).

## Architecture

This is a Spring Boot integration library that replaces Tomcat/Jetty with a Netty-based web server using Java virtual threads (Project Loom).

**Modules (flat layout, all included in settings.gradle.kts):**

- **netty-loom-spring-core** — Pure Netty layer, no Spring dependency. Netty transport, codec-http, handler, plus native epoll/kqueue transports.
- **netty-loom-spring-mvc** — Spring MVC servlet bridge. Contains `NettyLoomServletContext` (Jakarta ServletContext implementation) that allows Spring MVC to run on top of Netty.
- **netty-loom-spring-boot-starter** — Spring Boot auto-configuration entry point. `NettyWebServerFactory` implements `ServletWebServerFactory` SPI; `NettyWebServer` implements `WebServer`. Auto-config registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- **netty-loom-spring-example-tomcat** / **netty-loom-spring-example-netty** — Example apps (placeholders).

**Package root:** `io.azholdaspaev.nettyloom`

## Build System Details

- **Gradle 9.4.1**, Kotlin DSL, Java 25 toolchain
- **Spring Boot BOM 4.0.5** imported via `io.spring.dependency-management` plugin — Spring/Jakarta deps need no explicit version
- **Version catalog** at `gradle/libs.versions.toml` for Netty 4.2.12.Final, JUnit 6.0.3, etc.
- Native transport deps use classifier variants: `variantOf(libs.netty.transport.native.epoll) { classifier("linux-x86_64") }`
- `the<DependencyManagementExtension>()` doesn't work inside `subprojects {}` — use `pluginManager.withPlugin("io.spring.dependency-management") { configure<...> {} }` instead
- `libs` version catalog accessor is not available inside `subprojects {}` blocks — extract needed values to `val` at root level first

## CI

GitHub Actions (`.github/workflows/build.yml`): builds on push to main and PRs, uses Temurin JDK 25.
