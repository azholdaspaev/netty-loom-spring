# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew build                              # Full build (compile + test)
./gradlew build -x test                      # Build without tests
./gradlew :netty-loom-spring-core:test       # Test a single module
./gradlew :netty-loom-spring-boot-starter:test --tests 'io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.test.SmokeControllerTest'  # Single test
```

A failed test's stdout is not in the build output: read `<module>/build/test-results/test/TEST-*.xml`, or pass `-PverboseTests` to stream it.

Java 25 toolchain (LTS). No `--enable-preview` — the library targets only stable JDK features, so consumers don't need special JVM flags.

javac runs `-Xlint:all -Werror`, so a new warning is a red build: fix the code, and suppress at the site with a trigger-1 comment only when an external API forces it. Never widen the `-Xlint` exclusions in `build.gradle.kts`.

## Architecture

A Spring Boot integration library that replaces Tomcat/Jetty with a Netty-based web server using Java virtual threads (Project Loom). **Dependency flow:** `starter → mvc → core`; `core` has no Spring dependency.

| Module | Role |
| --- | --- |
| `netty-loom-spring-core` | Pure Netty, no Spring. `NettyServer` manages lifecycle; `NettyServerChannelInitializer` applies `NettyPipelineDefinition`, a list of `NettyPipelineStep`s, to each new channel's pipeline. Netty transport, codec-http, handler, native epoll/kqueue |
| `netty-loom-spring-mvc` | Spring MVC servlet bridge. `NettyServletContext` (Jakarta `ServletContext` with default `UnsupportedOperationException` stubs) and `DefaultNettyServletContext`; `SpringHttpRequestDispatcher` wraps `DispatcherServlet` with `NettyHttpServletRequest` / `NettyHttpServletResponse` |
| `netty-loom-spring-boot-starter` | Auto-configuration. `NettyWebServerFactory` implements the `ServletWebServerFactory` SPI, `NettyWebServer` implements `WebServer`; registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| `netty-loom-spring-example-netty` / `-tomcat` | Runnable Boot apps (`bootRun`), each with a `BenchmarkController` (`/ping`, `/work`) and an e2e test; ports 18080 and 18081/18082 (`platform`/`virtual` profiles). Load targets for the benchmark harness; `-tomcat` is the baseline |
| `netty-loom-spring-benchmarks` | k6 harness — **not a Gradle module** (no `src/`, absent from `settings.gradle.kts` by design). README.md § Benchmarks says how to run it and read the numbers |

**Seams:** `core.handler.HttpRequestDispatcher` separates the Netty pipeline from any higher-layer dispatcher and keeps `core` free of Spring. README.md § Architecture has the request flow and the pipeline handler names.

**Package root:** `io.github.azholdaspaev.nettyloomspring`. **Maven coordinate:** `io.github.azholdaspaev:netty-loom-spring-boot-starter`.

## Build System

Gradle 9.4.1 (Kotlin DSL), Spring Boot BOM 4.0.5, Netty 4.2.12.Final, JUnit 6.0.3; versions live in `gradle/libs.versions.toml`. The BOM-handling and `subprojects {}` rules load from `.claude/rules/gradle.md` whenever a build file is read.

## IDE Tooling

Library sources — Netty, Spring, the Servlet API, Tomcat — come from `./gradlew dependencySources`, which unpacks every `-sources.jar` on a module's `testRuntimeClasspath` into `<module>/build/dependency-sources/<artifact>-<version>/` (e.g. `netty-loom-spring-mvc/build/dependency-sources/spring-webmvc-<version>/org/springframework/web/servlet/DispatcherServlet.java`). Run it once per worktree; a second run is up-to-date, and `build` never runs it. Read the trees with `grep` and `cat`, the same as the source tree. `javap` gives signatures, never method bodies.

A JetBrains MCP server (`idea`) is available whenever IntelliJ has this project open; CI and the agent pipeline never have it. Reach for it only for inspections `javac` lacks — `ToolSearch("select:mcp__idea__lint_files")`, then **`lint_files` / `get_file_problems`**: `@Incubating` API use, JSpecify `@NullMarked` violations, superseded idioms. Gate at `min_severity: "error"`; `"warning"` adds unused-lambda-parameter noise. A clean file is omitted from `items`, not returned empty.

`./gradlew` is the ground truth for builds and tests, not the IDE. On an index error (`PSI and index do not match`) fall back to `grep`. `rename_refactoring` is denied in `.claude/settings.json`: it misses `META-INF` registrations and rewrites README prose, and the result still compiles. Cross-module renames are manual.

## Development Workflow

All source code changes must strictly follow TDD (Test-Driven Development): write a failing test first, then write the minimal production code to make it pass, then refactor. Never write production code without a corresponding test already in place.

Tests use JUnit 6 (`org.junit.jupiter.api`, via `org.junit.jupiter:junit-jupiter`) on JUnit Platform. All test tasks are configured with `useJUnitPlatform()` and run with `--enable-native-access=ALL-UNNAMED`.

`AGENTS.md` names the commit, pull request and issue templates, and is normative for their use. `.githooks/commit-msg` enforces the commit shape once `core.hooksPath` points at it (`CONTRIBUTING.md` § Commits); a clone without it has only the template. `.claude/scripts/check-comments.sh` enforces the part of rule 5 a script can count — the three numeric budgets at their ceilings, and the block form for multi-line comments — as the `commentBudget` task under `check` and as a `PostToolUse` hook; whether a class javadoc earned its raised ceiling, and the triggers, stay with the reviewer. `.claude/scripts/check-naming.sh` is its sibling for rule 7 — article, test prefix, the 60-character budget, a production method starting with a verb from the table's **Not** column, a case collision — and is under neither `check` nor the hook until the test renames land (#282); run it by hand over the files a change touches. The hook remaps `check-comments.sh`'s exit 1 to 2 because on `PostToolUse` only exit 2 puts the hook's stderr in front of the agent (Claude Code hooks reference, § Hook exit codes: https://code.claude.com/docs/en/hooks).

`.claude/settings.json` tracks the permission rules every session starts with; the body and review threads of #230, the pull request that added them, record why each rule has the shape it does, and which tidier spelling would strand the pipeline.

`scripts/agent/pipeline.sh <issue>` takes an issue from its worktree to a pull request ready for review: `stage.sh` runs each `/flow:*` command as an unattended `claude -p` with `.claude/agent/` as its system prompt and settings (`implement`, then `review`/`fix` until no review thread is open or a round changed nothing, three rounds at most, then `test`), and the script itself settles a reused worktree first (uncommitted edits to a named stash, unpushed commits to a draft pull request it opens in place of an implement stage), moves an issue whose stage asked a question to `agent/needs-input`, or marks the pull request ready, labels the issue `agent/pr-ready` and posts the cost and time; `requeue.sh` is the tick step that moves an answered `agent/needs-input` issue back to `agent/queued`, or only takes the label off when an `agent/fix` pull request is open on its branch; `runner.sh` is the launchd tick itself — under a lock: fail every issue a dead tick left on `agent/running` and strip `agent/*` labels from closed issues, clean up merged pull requests, `requeue.sh`, a fix stage then a review stage per `agent/fix` pull request whose issue is not on `agent/needs-input` (a question from either stage puts it there), then one `agent/queued` issue into a worktree under `../netty-loom-wt/`. `docs/agent-pipeline.md` has the labels, the plist and the maintainer's side. Each script has its own shim-driven `test-*.sh`. In that settings file `gh`, `git push` and `pr-comments.sh` are `sandbox.excludedCommands`: under Seatbelt a Go binary cannot verify TLS and SSH cannot cross the sandbox proxy (Claude Code sandboxing reference, § Troubleshooting: https://code.claude.com/docs/en/sandboxing). They still pass the permission rules.

## CI

`.github/workflows/build.yml` runs `./gradlew build` on ubuntu and macos; both matrix cells must pass to merge.

## Code Review

Review runs in two passes. The bug pass (`/code-review`) is tuned for correctness recall: it requires a concrete failure scenario per finding and discards style and quality findings. The maintainability pass is the `maintainability-pass` skill, which owns the lenses for naming consistency, method naming, magic constants, duplication, comment budget, simplicity and scope, and module boundaries. State-dependent correctness — concurrency, lifecycle, time arithmetic — is rule 6 below, so that either pass can quote it. `/flow:review` runs both and posts what survives as inline comments, whether a maintainer invokes it or the agent pipeline does.

## Guidelines

Tradeoff: these rules bias toward caution over speed. For trivial tasks, use judgment.

1. **Think Before Coding.** State assumptions explicitly. If multiple interpretations exist, present them — don't pick silently. If a simpler approach exists, say so; push back when warranted. If something is unclear, stop, name what is confusing, and ask.

2. **Simplicity First.** Minimum code that solves the problem, nothing speculative: no features beyond what was asked, no abstractions for single-use code, no flexibility or configurability nobody requested, no error handling for impossible scenarios. If a senior engineer would call it overcomplicated, simplify.

3. **Surgical Changes.** Touch only what you must. Don't improve adjacent code, comments or formatting; don't refactor what isn't broken; match existing style even if you'd do it differently. Mention unrelated dead code — don't delete it. Remove only what *your* change orphaned: imports, variables, functions. The test: every changed line traces directly to the user's request.

4. **Goal-Driven Execution.** Turn the task into a verifiable goal — "add validation" → "write tests for invalid inputs, then make them pass"; "fix the bug" → "write a test that reproduces it, then make it pass"; "refactor X" → "tests pass before and after". For multi-step work, state a numbered plan in which each step names its own verification.

5. **Comment With Restraint.** The source code is the only source of truth. Default to no comment.

When code and comment conflict, the code is right and the comment is a bug. Comment only what the source cannot state — a fact from outside this repository, or a choice the code cannot show because the rejected alternative is absent by definition. The reasoning behind this rule, and why the javadoc jar (#30) did not widen its budgets, is recorded in `docs/adr/0002-comment-budget.md`.

**Default: no comment, no javadoc.** Write one only when a trigger fires. "A reader might appreciate this" is not a trigger - if the code needs explaining, rename or restructure first.

**Triggers.** This list is closed. If none applies, write nothing.

1. An external contract dictates the shape - a spec clause, another container's behaviour (Tomcat, Jetty), a Netty or Servlet API quirk. Something unrecoverable from this repository. Cite the source.
2. A decision with a named rejected alternative, written in the house shape *X rather than Y, because Z*: "Hex, not Base64: `ServerCookieEncoder.STRICT` throws on octets outside the RFC 6265 cookie-value set." If you cannot name Y, there was no decision; delete it.
3. A concurrency or lifecycle invariant - what guards this state, which thread this runs on, why this is safe to run twice.
4. A deliberate deviation from what the surrounding code or an inherited javadoc predicts.

**Budgets.** Hard numbers, so a reviewer can quote them.

- Class javadoc: 8 lines. Trigger 1, and only trigger 1, with the external source cited, raises it to 20. Past 20 lines it is a design document - put it in `docs/adr/` and leave a one-line pointer.
- `private` members: 2 lines, invariant only, and only when that invariant is unguessable from the type and name. A private method's rationale belongs at the one call site that needs it, not above the method.
- `@Test` methods: none. The test name and the assertion message carry the meaning; lengthen the assertion message rather than add a comment. A genuine harness trap - why the test would pass against a broken version - goes in the test body as a `/* */` block.

**Form.** A single line is `//`. Anything longer is a block: `/** */` when it documents a declaration, `/* */` inside a body. Two consecutive full-line `//` lines fail `check-comments.sh`, a `// --- Name ---` banner included when another `//` line follows it — a `//` run reads as disabled code and is invisible to the budgets above.

**One owner per fact; link rather than restate.** Rationale for class X lives in X. If you find yourself writing "the reason is recorded there", stop - you have just proved the paragraph is redundant. Link and delete it. The Duplication lens applies to comments across files.

**Never.** Each is a quotable violation:

- A paragraph about code that does not exist yet - a pending issue, a planned handler.
- A count or census a later change silently falsifies ("that is twelve of the fourteen events").
- Rebutting a claim nobody made ("it is tempting to state the stronger fact...").
- A comment restating the line beneath it.
- A `<p>` chain where one sentence would do. Multi-paragraph javadoc is the exception; today's files are not evidence that it is the norm.

**Rewrite, never append.** When a change makes a comment stale or incomplete, rewrite that comment. Do not stack a second paragraph beside it - comments that accrete paragraphs stop being read.

**Subtract before you finish.** Reread every comment you added and delete each one whose trigger you cannot name. This is a required step, not advice; the change is not done until it has run.

**Overrides rule 3 for volume.** For comment volume this rule wins over "match existing style": the neighbouring file is not evidence of anything, and the heaviest files are a ceiling already set too high, never a target.

Keep `#NN` issue citations and `// --- Name ---` section banners. Neither is prose, and neither is what this rule bounds.

6. **Concurrency and time.** Shared mutable state reachable from more than one thread must name what guards it. Do not synchronize on an object that callers can also reach — lock on a private, dedicated monitor. Re-check a condition after taking the lock that protects it; a check made before the lock is already stale when acted on. Eviction, cleanup and shutdown paths must be safe to run twice and safe to run concurrently with what they are tearing down, and anything registered at startup must survive a stop/start cycle in the same JVM without duplicate registration or a listener left bound to a dead context. Durations, deadlines and timeouts must not overflow or turn negative when a configured value is large, zero or unset. Compare instants by elapsed difference, never by a sum that can wrap. A review finding here is in scope precisely *because* it depends on timing, interleaving or accumulated state.

7. **Name methods for the reader.** An identifier is not prose. The rationale, and why the migration runs per module (#280–#283), is recorded in `docs/adr/0003-method-naming.md`.

Applies to every method, production and test:

- **No articles.** `a`, `an`, `the` never appear as a word in a method name: `rejectHeaderOverLimit`, not `rejectAHeaderOverTheLimit`.
- **No filler prefix.** `test`, `verify`, `check`, `ensure` do not start a test name; `should` is the only prefix a test carries.
- **Budget: 60 characters.** Past it the name is carrying a condition the class name or a parameterized test should carry.

Production:

- Imperative verb first: `applyTo`, `awaitDrained`, `closeIfIdle`. A noun-only name is an accessor and nothing else.
- Accessors: `getX`/`setX`/`isX`/`hasX` on classes; bare `x()` only on records and their wrappers. Never both on one class.
- One verb per concept. The vocabulary is closed; extend it in this table, not in a file:

  | Concept | Verb | Not |
  | --- | --- | --- |
  | throw unless a precondition holds | `require*` | `verify*`, `check*` |
  | notify registered listeners | `fire*` | `notify*` |
  | record a state transition | `mark*` | a past participle as a command (`*Started`, `*Finished`) |
  | construct a fresh instance | `new*` | `create*`, `build*` |
  | convert from or to another type | `from(x)` / `toX()` | `as*` |
  | lazily initialise, idempotent | `ensure*` | — |

- Names an external interface dictates (`shutDownGracefully`, `initChannel`, Servlet `getInitParameter`) are outside the rule; the method implementing it must not share the name in another case. An inbound event callback the caller names (`exchangeStarted`) is not a `mark*` command; the ADR lists the ones that stay.

Tests:

- Shape: `should<Verb><Outcome>[<When|Once|After|While><Condition>]` — verb in base form, outcome before condition: `shouldRejectHeaderOverLimitWith431`, `shouldCloseConnectionOnceLastOwedResponseIsWritten`.
- The subject is the class under test. When the name is ambiguous without a method name, the method becomes the condition: `shouldCommitResponseOnSendError`, not `shouldSendErrorCommitResponse`.
- No `@DisplayName`: the method name is the display name, so the budget cannot be evaded by moving prose into an annotation.
