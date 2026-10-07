# Contributing

Thanks for considering a contribution. [`CLAUDE.md`](CLAUDE.md) is the normative document for how
changes are made here — the build commands, the module map, the test-first rule, the comment
budget, the naming rule — and reviewers quote it by section and rule number. This file holds only
what it does not: getting a clone ready, and the shape of a commit and a pull request.

## Prerequisites

- **JDK 25 installed.** Gradle picks the toolchain out of the JDKs already on the machine, so the
  wrapper itself can be launched by any JDK 17+ — but a 25 must be present somewhere. Toolchain
  auto-provisioning is not configured, so a missing 25 fails the build rather than downloading one.
- Nothing else — the Gradle wrapper is checked in.

`CLAUDE.md` § Build Commands has the build and test invocations; the README's
[Quick start](README.md#quick-start) runs the example server by hand.

## How a change is made

`CLAUDE.md` § Architecture says where a change belongs, § Development Workflow states the
test-first rule, and § Guidelines is the bar each edit must clear — rule 3 for what not to touch
(an unrelated problem is mentioned, never fixed in the same pull request), rule 5 for comments,
rule 7 for names, rule 8 for documents. The pull request template's checklist is the same bar in
the order you will be asked about it.

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

[`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md) is the body shape. GitHub
fills the compose box from it, as does a bare `gh pr create`; `gh pr create --body` bypasses it, so
read the file directly. Because commits are capped at 500 characters, the pull request body is
where the reasoning lives — why the change is right, the alternative you rejected, and the
mutations you ran with what each one broke.

Merging needs both CI cells green (`CLAUDE.md` § CI). Review is advisory: `/flow:review <PR>` from
a Claude Code session posts a bug pass and a maintainability pass as inline comments, and nothing
in CI triggers it.

## Benchmarks

The k6 harness and how to read its numbers:
[`netty-loom-spring-benchmarks/README.md`](netty-loom-spring-benchmarks/README.md).

## Architecture decisions

A rule other contributors must follow goes in `CLAUDE.md`; its rationale, when it needs more than
a sentence, goes in an ADR under [`docs/adr/`](docs/adr). `CLAUDE.md` § Guidelines rule 8 has the
shape, and what an ADR must not hold.

## License

Contributions are accepted under the [Apache License 2.0](LICENSE).
