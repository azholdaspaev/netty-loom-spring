# AGENTS.md

For any coding agent working in this repository — Claude, Codex, Cursor, Copilot or another. This
file holds no rules that already live elsewhere; it points at the files that do.

- [`CLAUDE.md`](CLAUDE.md) is normative for how changes are made: the test-first rule, the comment
  budget, the module boundaries, the review lenses. Read it before changing source.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) is the practical summary of the same ground.

## Authoring formats

Use the file. Do not reinvent the shape, and do not infer it from `git log` or `gh issue view` —
history is not a specification.

English only, whatever language the conversation with the agent is in: identifiers, comments,
commit messages, issues, pull requests and review replies.

| Artefact | Template |
| --- | --- |
| Commit message | [`.gitmessage`](.gitmessage) |
| Pull request | [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md) |
| Issue | [`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE) — `task.md` or `bug.md` |

## One rule that survives the bypass

`git commit -m` never opens `.gitmessage`, and `gh pr create --body` ignores the pull request
template. `.githooks/commit-msg` catches the first once `core.hooksPath` points at it
(`CONTRIBUTING.md` § Commits); nothing catches the second. So one rule holds on both with no
template loaded to state it:

- **No trailers, on a commit or on a pull request body.** No session or transcript link from any
  tool (`claude.ai`, Codex, Cursor or another), no `Co-authored-by` bot line, no generated-by
  footer.
