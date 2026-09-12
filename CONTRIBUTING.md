# Contributing

Thanks for considering a contribution. This project has a small number of conventions that are
enforced rather than suggested; the two that surprise people are the **test-first rule** and the
**comment budget**. Both are described in full in [`CLAUDE.md`](CLAUDE.md), which is the normative
document for how changes are made here. This file is the practical summary.

## Prerequisites

- **JDK 25 installed.** Gradle picks the toolchain out of the JDKs already on the machine, so the
  wrapper itself can be launched by any JDK 17+ — but a 25 must be present somewhere. Toolchain
  auto-provisioning is not configured, so a missing 25 fails the build rather than downloading one.
- Nothing else — the Gradle wrapper is checked in.

## Build and test

```bash
./gradlew build                              # compile + test, everything
./gradlew build -x test                      # compile only
./gradlew :netty-loom-spring-core:test       # a single module
./gradlew :netty-loom-spring-boot-starter:test --tests 'io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.test.SmokeControllerTest'
```

A failed test's stdout is not in the build output: read `<module>/build/test-results/test/TEST-*.xml`,
or pass `-PverboseTests` to stream it.

javac runs `-Xlint:all -Werror`, so a new warning is a red build: fix the code, and suppress at
the site with a trigger-1 comment only when an external API forces it. Never widen the `-Xlint`
exclusions in `build.gradle.kts`.

Tests use JUnit 6 on the JUnit Platform and run with `--enable-native-access=ALL-UNNAMED`, which
the native epoll and kqueue transports need.

To run the server by hand:

```bash
./gradlew :netty-loom-spring-example-netty:bootRun    # :18080
```

## Where a change belongs

The dependency flow is `starter → mvc → core`, and **`core` must not gain a Spring dependency**.
`HttpRequestDispatcher` is the seam that keeps it that way; a change
that couples across a layer or routes around a seam will be sent back even if it compiles.
`CLAUDE.md` § Architecture has the module map.

Cross-cutting Netty behaviour gets its own `Http*Handler` pipeline step rather than being bolted
onto an existing handler.

## Test-driven development is required

Write a failing test, then the minimal production code that makes it pass, then refactor. **Never
write production code without a test already in place for it.** This is not a preference — a pull
request whose production change has no test that fails without it will not be merged.

A corollary worth stating, because it is where most contributions fall short: verify your tests by
mutation. Break the production code you just wrote and confirm the new test actually fails. A test
that passes against a broken implementation is not coverage.

## Comments

The default is **no comment and no javadoc**. The source is the only artefact the compiler checks
and the tests bind, so a comment is unverified prose that drifts silently. Write one only when it
records something the source cannot state: an external contract (a spec clause, a Netty or Servlet
API quirk), a decision with a named rejected alternative, a concurrency or lifecycle invariant, or a
deliberate deviation from what the surrounding code predicts.

`CLAUDE.md` § Guidelines rule 5 has the closed list of triggers and the hard line budgets. Read it
before adding any comment; reviewers quote it.

`./gradlew build` runs [`.claude/scripts/check-comments.sh`](.claude/scripts/check-comments.sh),
which rejects the part a script can check — a javadoc on a `@Test` method, a class javadoc past 20
lines, a `private` member's past 2 — naming the file and line; whether a trigger fired, and the
8-line class budget, stay with the author.

## Commits

Subject line: `NL-<issue number> <imperative summary>`, e.g.

```
NL-148 Put the servlet types consumers compile against on the api surface
```

[`.gitmessage`](.gitmessage) owns the full shape — the 500-character cap on the whole message,
no trailers, one idea per commit. Once `core.hooksPath` points at it,
[`.githooks/commit-msg`](.githooks/commit-msg) rejects the part a script can check — key, lengths,
ASCII, trailers — whichever way the message was written, `git commit -m` included; one idea per
commit stays with the author. Load both once:

```bash
git config commit.template .gitmessage
git config core.hooksPath .githooks
```

Branch names follow the same key: `NL-<issue number>-<short-slug>`.

## Pull requests

[`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md) is the body shape, and it
carries the checklist to work through before opening. GitHub fills the compose box from it, as
does a bare `gh pr create`; `gh pr create --body` bypasses it, so read the file directly.

Because commits are capped at 500 characters, the pull request body is where the reasoning lives —
why the change is right, the alternative you rejected, and the mutations you ran with what each
one broke.

CI runs `./gradlew build` on both Linux and macOS so that epoll and kqueue are each exercised.
**Both matrix cells must pass to merge.**

Review is `/flow:review <PR>` from a Claude Code session on a checkout with dependency sources
unpacked (`./gradlew dependencySources`): a bug pass, then a maintainability pass, posted as one
advisory review of inline comments. It is never a required check; there is no CI review to
trigger.

## Unrelated problems you notice

Mention them; do not fix them in the same pull request. Dead code you did not create stays until
someone asks for it. If your own change orphans an import, a variable or a method, remove it — that
is your mess, and cleaning it is expected.

## Benchmarks

`netty-loom-spring-benchmarks/` is a k6 harness, deliberately **not** a Gradle module — it has no
`src/` and is absent from `settings.gradle.kts`. It drives the two example apps over HTTP. Its own
[README](netty-loom-spring-benchmarks/README.md) covers prerequisites, the sweep script and how to
read the output.

Benchmark numbers are noisy: run-to-run variance is around ±11%, so a single forward pass can read
a run-order effect as a regression. Quiet the machine and cross over before reporting a difference.

## Architecture decisions

Anything that sets a rule other contributors must follow — a namespace, an ownership boundary, a
protocol deviation — belongs in an ADR under [`docs/adr/`](docs/adr), not in a comment or a commit
body. [ADR 0001](docs/adr/0001-server-properties-namespace.md) is the model.

## License

Contributions are accepted under the [Apache License 2.0](LICENSE).
