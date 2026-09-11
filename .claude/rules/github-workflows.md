---
paths:
  - ".github/**"
---

# GitHub Actions and rulesets

- `build.yml` builds on push to `main` and on PRs with Temurin JDK 25, on ubuntu and macos so that epoll and kqueue are each exercised. The job name and matrix values are the required-check contexts in `.github/rulesets/main-required-ci.json`; rename either only in the same PR as a ruleset update. `.github/rulesets/README.md` owns why the two rulesets are split and how to apply them (`PUT`, never `POST`)
- `publish.yml` publishes to Sonatype: `workflow_dispatch` publishes a snapshot, a `v*.*.*` tag push publishes a release, and the two never overlap. Neither job is a required check. Why each path is shaped the way it is — no snapshot on push to `main`, no `maven-metadata.xml` in the bundle, `USER_MANAGED` rather than `AUTOMATIC` — is recorded at the steps themselves; the Portal-side state they depend on is in `docs/publishing.md` (#31)
- `claude-review.yml` runs the bug pass (`/code-review:code-review --comment`) and then the `maintainability-pass` skill on any pull request labelled `review/claude`. It skips drafts and forks, posts findings as inline review comments, and is deliberately not a required status check. Its comments are authored by `github-actions[bot]`, which its dedupe step and the summary count both depend on; the rationale for every input is a comment beside that input
