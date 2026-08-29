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

Tests use JUnit 6 on the JUnit Platform and run with `--enable-native-access=ALL-UNNAMED`, which
the native epoll and kqueue transports need.

To run the server by hand:

```bash
./gradlew :netty-loom-spring-example-netty:bootRun    # :18080
```

## Where a change belongs

The dependency flow is `starter → mvc → core`, and **`core` must not gain a Spring dependency**.
`HttpRequestDispatcher` and `NettyPipelineConfigurer` are the seams that keep it that way; a change
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

## Commits

Subject line: `NL-<issue number> <imperative summary>`, e.g.

```
NL-148 Put the servlet types consumers compile against on the api surface
```

**The whole message is 500 characters or fewer** — subject, body and `Closes #NN` together — and
the body is a short description of what the change makes true. Why the change is right, the
alternative you rejected and the mutations you ran belong in the pull request body, which is the
artefact reviewers actually read. Commits carry no trailers: no session links from any agent, no
`Co-authored-by` bot lines, no generated-by footers.

[`.gitmessage`](.gitmessage) has the full shape. Load it once with:

```bash
git config commit.template .gitmessage
```

Git fills the editor from it on a bare `git commit`. `git commit -m` bypasses it, so the file is
written to be read directly as well.

Branch names follow the same key: `NL-<issue number>-<short-slug>`.

Keep each commit to one idea. A changed line that does not trace to the issue in its subject
belongs in a different commit — that rule is applied literally.

## Pull requests

Before opening:

- [ ] `./gradlew build` passes locally
- [ ] Every production change has a test that fails without it, and you have confirmed that by
      mutation
- [ ] Every comment you added fires a trigger from `CLAUDE.md` rule 5, and you can name which one
- [ ] Every changed line traces to the issue
- [ ] Documentation that the change falsifies is rewritten, not appended to — including
      [`README.md`](README.md), [`docs/compatibility-matrix.md`](docs/compatibility-matrix.md) and
      [`docs/configuration.md`](docs/configuration.md) when behaviour or a property changes
- [ ] A user-visible change has a [`CHANGELOG.md`](CHANGELOG.md) entry

CI runs `./gradlew build` on both Linux and macOS so that epoll and kqueue are each exercised.
**Both matrix cells must pass to merge.**

Applying the `review/claude` label additionally runs an automated two-pass review that leaves
inline comments. It is advisory, never a required check, and it does not run on drafts or forks.

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
