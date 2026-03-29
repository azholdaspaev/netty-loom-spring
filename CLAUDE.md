# CLAUDE.md

## Project

Netty-based HTTP server for Spring Boot using Java virtual threads (Project Loom). Replaces Tomcat with a Netty pipeline while keeping full Spring MVC compatibility.

## Build

```bash
./gradlew build          # Full build + tests + spotless check
./gradlew test           # Tests only
./gradlew :netty-loom-spring-core:test   # Single module
./gradlew spotlessApply  # Format code
./gradlew spotlessCheck  # Verify formatting
```

- Java 24 with `--enable-preview` — all compile/test/javadoc tasks need it
- Gradle 8.14, Kotlin DSL
- Spring Boot 3.4.13 BOM via `io.spring.dependency-management` plugin
- Versions in `gradle/libs.versions.toml`

## Module Architecture

```
core  ←  mvc  ←  starter  ←  examples
```

- **core** — Netty server, handlers, HTTP abstractions. **No Spring or Jakarta Servlet deps** (enforced by ArchUnit).
- **mvc** — Spring MVC integration, servlet bridge. Depends on core.
- **starter** — Spring Boot auto-configuration. Depends on core + mvc. Excludes Tomcat.
- **example-netty**, **example-tomcat** — `application` plugin, not `java-library`.

## Gradle Gotchas

- `the<DependencyManagementExtension>()` doesn't work in `subprojects{}` — use `pluginManager.withPlugin("io.spring.dependency-management") { configure<...> {} }`
- Spring BOM-managed deps need no version in `libs.versions.toml`
- `libs` accessor not available inside `subprojects {}` — extract to `val` at root level
- Native transport: `variantOf(libs.netty.transport.native.epoll) { classifier("linux-x86_64") }`

## Code Style & Testing

- Spotless + Palantir Java Format 2.86.0 — always run `./gradlew spotlessApply` before committing
- See [conventions.md](conventions.md) for naming, class design, Netty patterns, testing rules, and CI details

## Commit Messages

- Imperative mood, concise: "Fix keep-alive issue", "Add support for REST methods"
- One primary action per commit
