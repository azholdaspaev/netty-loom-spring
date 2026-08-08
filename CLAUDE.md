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
- **Comment budget.** Judge against rule 5's triggers and budgets. A comment firing no listed trigger is a finding, and so is one over budget — name the number it breaks. In scope: class javadoc past 8 lines (20 with a cited external contract), javadoc on a `private` member past 2 lines, javadoc on a `@Test`, rationale restated in a file that does not own it, and a paragraph about code that does not exist. Two shapes to look for: *volume outrunning the decision being recorded*, and *prose the diff has just falsified* — a comment the change made wrong or incomplete, or one asserting a count, a sole call site or an exhaustive list that a later edit will silently break. Since the source is the only source of truth, a comment contradicted by the code beside it is a correctness finding, not a nitpick.
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

5. Comment With Restraint
   The source code is the only source of truth. Default to no comment.

The code is the sole artefact that executes, that the compiler checks and that the tests bind. A comment is unverified prose sitting beside it: nothing fails when the two disagree, so drift is silent and permanent. When code and comment conflict, the code is right and the comment is a bug. Three consequences, and they are the reason for everything below:

Never describe what the code does. The code already says that, correctly, and forever. Such a comment adds no information the day it is written and becomes false later.
A comment a later edit can silently falsify is a liability, not documentation. Counts, censuses, lists of call sites and "the only place that..." all decay unnoticed.
Comment only what the source cannot state - a fact from outside this repository, or a choice the code cannot show because the rejected alternative is absent by definition.

**Default: no comment, no javadoc.** Write one only when a trigger fires. "A reader might appreciate this" is not a trigger - if the code needs explaining, rename or restructure first.

**Triggers.** This list is closed. If none applies, write nothing.

1. An external contract dictates the shape - a spec clause, another container's behaviour (Tomcat, Jetty), a Netty or Servlet API quirk. Something unrecoverable from this repository. Cite the source.
2. A decision with a named rejected alternative, written in the house shape *X rather than Y, because Z*: "Hex, not Base64: `ServerCookieEncoder.STRICT` throws on octets outside the RFC 6265 cookie-value set." If you cannot name Y, there was no decision; delete it.
3. A concurrency or lifecycle invariant - what guards this state, which thread this runs on, why this is safe to run twice.
4. A deliberate deviation from what the surrounding code or an inherited javadoc predicts.

**Budgets.** Hard numbers, so a reviewer can quote them.

- Class javadoc: 8 lines. Trigger 1, and only trigger 1, with the external source cited, raises it to 20. Past 20 lines it is a design document - put it in `docs/adr/` and leave a one-line pointer.
- `private` members: 2 lines, invariant only, and only when that invariant is unguessable from the type and name. A private method's rationale belongs at the one call site that needs it, not above the method.
- `@Test` methods: none. The test name and the assertion message carry the meaning; lengthen the assertion message rather than add a comment. A genuine harness trap - why the test would pass against a broken version - goes in the test body as a `//` block.

**One owner per fact; link rather than restate.** Rationale for class X lives in X. If you find yourself writing "the reason is recorded there", stop - you have just proved the paragraph is redundant. Link and delete it. The Duplication lens applies to comments across files.

**Never.** Each is a quotable violation:

- A paragraph about code that does not exist yet - a pending issue, a planned handler.
- A count or census a later change silently falsifies ("that is twelve of the fourteen events").
- Rebutting a claim nobody made ("it is tempting to state the stronger fact...").
- A comment restating the line beneath it.
- A `<p>` chain where one sentence would do. Multi-paragraph javadoc is the exception; today's files are not evidence that it is the norm.

**Rewrite, never append.** When a change makes a comment stale or incomplete, rewrite that comment. Do not stack a second paragraph beside it - comments that accrete paragraphs stop being read.

**Subtract before you finish.** Reread every comment you added and delete each one whose trigger you cannot name. This is a required step, not advice; the change is not done until it has run.

**Overrides rule 3 for volume.** Rule 3 says match existing style; for comment volume this rule wins. Density here is bimodal - past 50% in some files, zero in others - so the neighbouring file is not evidence of anything. The heaviest files are a ceiling already set too high, never a target. Nothing ships as a javadoc jar today, so javadoc's only reader is someone already looking at the source; if one ever ships, revisit this rule.

Keep `#NN` issue citations and `// --- Name ---` section banners. Neither is prose, and neither is what this rule bounds.

