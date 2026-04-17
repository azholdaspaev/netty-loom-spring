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

## Guidelines

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

Tradeoff: These guidelines bias toward caution over speed. For trivial tasks, use judgment.

1. Think Before Coding
   Don't assume. Don't hide confusion. Surface tradeoffs.

Before implementing:

State your assumptions explicitly. If uncertain, ask.
If multiple interpretations exist, present them - don't pick silently.
If a simpler approach exists, say so. Push back when warranted.
If something is unclear, stop. Name what's confusing. Ask.
2. Simplicity First
   Minimum code that solves the problem. Nothing speculative.

No features beyond what was asked.
No abstractions for single-use code.
No "flexibility" or "configurability" that wasn't requested.
No error handling for impossible scenarios.
If you write 200 lines and it could be 50, rewrite it.
Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

3. Surgical Changes
   Touch only what you must. Clean up only your own mess.

When editing existing code:

Don't "improve" adjacent code, comments, or formatting.
Don't refactor things that aren't broken.
Match existing style, even if you'd do it differently.
If you notice unrelated dead code, mention it - don't delete it.
When your changes create orphans:

Remove imports/variables/functions that YOUR changes made unused.
Don't remove pre-existing dead code unless asked.
The test: Every changed line should trace directly to the user's request.

4. Goal-Driven Execution
   Define success criteria. Loop until verified.

Transform tasks into verifiable goals:

"Add validation" → "Write tests for invalid inputs, then make them pass"
"Fix the bug" → "Write a test that reproduces it, then make it pass"
"Refactor X" → "Ensure tests pass before and after"
For multi-step tasks, state a brief plan:

1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
   Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

