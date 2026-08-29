# AGENTS.md

For any coding agent working in this repository — Claude, Codex, Cursor, Copilot or another. This
file holds no rules that already live elsewhere; it points at the files that do.

- [`CLAUDE.md`](CLAUDE.md) is normative for how changes are made: the test-first rule, the comment
  budget, the module boundaries, the review lenses. Read it before changing source.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) is the practical summary of the same ground.

## Authoring formats

Use the file. Do not reinvent the shape, and do not infer it from `git log` or `gh issue view` —
history is not a specification.

| Artefact | Template |
| --- | --- |
| Commit message | [`.gitmessage`](.gitmessage) |
| Pull request | [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md) |
| Issue | [`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE) — `task.md` or `bug.md` |

## Two rules that survive the bypass

`git commit -m` ignores `commit.template`, and `gh pr create --body` ignores the pull request
template. Both rules below hold on those paths too, where no template is loaded to state them:

- **A commit message is 500 characters or fewer** — subject, body and `Closes #NN` together — and
  its body is a short description. Why the change is right, the alternative you rejected and the
  mutations you ran go in the pull request body instead.
- **No trailers on a commit or a pull request body.** No session or transcript link from any tool
  (`claude.ai`, Codex, Cursor or another), no `Co-authored-by` bot line, no generated-by footer.
