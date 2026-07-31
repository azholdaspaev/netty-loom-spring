# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew build                              # Full build (compile + test)
./gradlew build -x test                      # Build without tests
./gradlew :netty-loom-spring-core:test       # Test a single module
./gradlew test --tests 'io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.test.SmokeControllerTest'  # Single test
```

Java 25 toolchain (LTS). No `--enable-preview` — the library targets only stable JDK features, so consumers don't need special JVM flags.

## Architecture

This is a Spring Boot integration library that replaces Tomcat/Jetty with a Netty-based web server using Java virtual threads (Project Loom).

**Gradle modules (flat layout, all included in settings.gradle.kts):**

- **netty-loom-spring-core** — Pure Netty layer, no Spring dependency. `NettyServer` manages lifecycle; `NettyServerChannelInitializer` delegates to `NettyPipelineConfigurer` SPI for channel pipeline setup. Netty transport, codec-http, handler, plus native epoll/kqueue transports.
- **netty-loom-spring-mvc** — Spring MVC servlet bridge. Contains `NettyServletContext` interface (Jakarta ServletContext with default UnsupportedOperationException stubs) and `DefaultNettyServletContext` implementation that allows Spring MVC to run on top of Netty.
- **netty-loom-spring-boot-starter** — Spring Boot auto-configuration entry point. `NettyWebServerFactory` implements `ServletWebServerFactory` SPI; `NettyWebServer` implements `WebServer`. Auto-config registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- **netty-loom-spring-example-netty** / **netty-loom-spring-example-tomcat** — Runnable Spring Boot apps (both apply the `spring.boot` plugin, so `bootJar`/`bootRun` work). Each has an application class, a `BenchmarkController` exposing `/ping` and `/work`, and a `@SpringBootTest` end-to-end test. `example-netty` depends on the starter (port 18080); `example-tomcat` depends on `spring-boot-starter-web` and is the comparison baseline, with `platform` and `virtual` profiles (ports 18081/18082). They are the load-generation targets for the benchmark harness below.

**Not a Gradle module** (deliberately absent from settings.gradle.kts, no `src/`):

- **netty-loom-spring-benchmarks** — k6 load-testing harness: `k6/*.js` scenarios, `scripts/run-all.sh`, and `results/`. Drives the example apps over HTTP; nothing here is compiled by Gradle. See README.md ("Benchmarks") for how to run it and how to read the numbers.

**Package root:** `io.github.azholdaspaev.nettyloomspring`

Sub-packages: `core.server`, `core.pipeline`, `core.handler`, `mvc.servlet`, `mvc.handler`, `autoconfigure`, `autoconfigure.server`

**Maven coordinate (target):** `io.github.azholdaspaev:netty-loom-spring-boot-starter`

**Core SPI:** `core.handler.HttpRequestDispatcher` is the seam between the Netty pipeline and any higher-layer dispatcher (keeps `core` free of Spring). `mvc.handler.SpringHttpRequestDispatcher` is the Spring MVC implementation, wrapping `DispatcherServlet` with `NettyHttpServletRequest` / `NettyHttpServletResponse`.

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

Tests use JUnit 6 (`org.junit.jupiter.api`, via `org.junit.jupiter:junit-jupiter`) on JUnit Platform. All test tasks are configured with `useJUnitPlatform()` and run with `--enable-native-access=ALL-UNNAMED`.

## CI

GitHub Actions (`.github/workflows/build.yml`): builds on push to main and PRs, uses Temurin JDK 25.

`.github/workflows/claude-review.yml` runs both passes described under "Code Review" — the bug pass first, then the maintainability pass — on any pull request labelled `review/claude`. It is gated on that label, skips drafts and forks, posts findings as inline review comments, and is deliberately not a required status check. The bug pass is designed to abort on a PR it has already commented on, which would leave a later push re-running only the maintainability pass — unverified here, since the workflow's comments are authored by `github-actions[bot]` rather than `claude[bot]`, and that check is the plugin's own judgement call.

## Code Review

The bug-focused review (`/code-review`) is tuned for correctness recall — it requires a concrete failure scenario per finding and ranks correctness above cleanup. Its default bar admits only failures that hold *regardless of inputs*, and it discards style, quality and "subjective" findings outright. That framing structurally under-weights two things: **state-dependent correctness bugs**, and *maintainability and consistency* issues, which have no crash and no quotable rule.

The lenses below close both gaps and are the second pass's brief. They are phrased as rules so that a violation can be quoted, which is what the bug pass requires before it will flag anything. Read whole files and across the module, not hunk-by-hunk.

**Correctness lenses.** These override the bug pass's "regardless of inputs" bar: a finding here is in scope precisely *because* it depends on timing, interleaving or accumulated state.

- **Concurrency and lifecycle.** Shared mutable state reachable from more than one thread must name what guards it. Do not synchronize on an object that callers can also reach — lock on a private, dedicated monitor. Re-check a condition after taking the lock that protects it; a check made before the lock is already stale when acted on. Eviction, cleanup and shutdown paths must be safe to run twice and safe to run concurrently with what they are tearing down, and anything registered at startup must survive a stop/start cycle in the same JVM without duplicate registration or a listener left bound to a dead context.
- **Time and counter arithmetic.** Durations, deadlines and timeouts must not overflow or turn negative when a configured value is large, zero or unset. Compare instants by elapsed difference, never by a sum that can wrap.

**Maintainability and consistency lenses.**

- **Naming consistency (convention-by-example).** New types should match the emergent naming of their siblings even when no written rule exists. Example: everything in `core.handler` is `Http`-prefixed (`HttpRequestHandler`, `HttpRequestDispatcher`, `HttpExceptionHandler`, `HttpConnectionMetadata`) — a new class there should carry the prefix.
- **Magic constants.** Flag bare literals that encode a named concept; prefer a named constant or an existing enum/util. Example: HTTP scheme names and their default ports come from `io.netty.handler.codec.http.HttpScheme` (`HttpScheme.HTTPS.toString()` → `"https"`, `HttpScheme.HTTPS.port()` → `443`) rather than hardcoded `"http"/"https"` or `80/443`. Sentinel values (e.g. `""`/`0` for an unknown address) should be named constants documenting their contract.
- **Duplication.** Two shapes, both in scope. *The same fact expressed twice* — one concept living in two places, the coupling a per-hunk scan misses; a scheme→port default living as strings in one class and as `80/443` in another is the *same* fact, so centralize it in one owner and have the other delegate. *The same logic expressed twice* — copy-pasted or near-identical blocks across classes or test fixtures, where a later fix to one will not reach the other.
- **Simplicity and scope.** Judge against the rules in the "Guidelines" section of this file — (2) Simplicity First and (3) Surgical Changes. Speculative abstraction, configurability nobody asked for, error handling for impossible states, and changed lines that do not trace to the stated goal are findings here, not taste.
- **Module boundaries.** Judge against the "Architecture" section of this file: the `starter → mvc → core` dependency flow, `core` carrying no Spring dependency, and `HttpRequestDispatcher` / `NettyPipelineConfigurer` as the seams. New coupling that crosses a layer or routes around a seam is a finding even when it compiles.

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

