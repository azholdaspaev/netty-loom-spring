# ADR 0003 — The shape of the tracked permission rules

- Status: Accepted
- Date: 2026-09-12
- Issue: [#212](https://github.com/azholdaspaev/netty-loom-spring/issues/212)
- File: `.claude/settings.json` § `permissions` (the rules themselves; this record holds only
  why each has the shape it does)

## Context

Before #212 every Bash permission rule lived in `settings.local.json`, which the maintainer's
global gitignore keeps out of every clone, so a fresh checkout, a pipeline worktree and an
unattended `claude -p` run started with no guard rail. `deny` is evaluated before `ask` and
`allow` at every settings level and is applied before workspace trust, so the tracked
`.claude/settings.json` is the one place a rule reaches every session, and a tracked deny cannot
be re-allowed from `settings.local.json`, `~/.claude/settings.json` or `--allowedTools`.

Rules are case-sensitive globs over the raw command text. A mid-pattern `*` matches the empty
string, so `git push *--force *` covers the flag in either position; a literal tail is honoured.
JSON cannot carry a comment, which is why the reasoning is here and not beside each rule.

## Decision

**`git push *--force` and `git push *--force *`, not `git push *--force*`.** The literal tail
keeps `--force-with-lease` open, which the agent needs to re-push its own branch after a rebase;
the `main-pull-request` ruleset already forbids a non-fast-forward on the default branch.
Tidying these two into `*--force*` to match the neighbouring rules silently denies the leased
push and strands the pipeline. `-f` has no leased form, so it is denied in both positions
(`git push -f*`, `git push * -f*`); `git push *-f*` was rejected because it would also deny any
branch name containing `-f`. The `+<refspec>` marker and `--mirror` are the same act by another
spelling and are denied outright.

**`git commit *--no-verify*` only; `-n` is not denied.** `git commit *-n*` would match every
`-m` message containing `-n`, and a false deny on a commit strands the pipeline. The commit-msg
hook this protects is a shape check, so the cost of the gap is bounded.

**Label flags on `gh pr edit` / `gh issue edit`, not the whole command.** #212 wrote
`gh pr edit*` so that only the pipeline script moves labels and state, but the agent rewrites its
own pull request body with the same command and a deny cannot carry an exception. The label
flags are separable: one rule per flag covers both `--add-label x` and `--add-label=x`, while
`--title`, `--body` and `--body-file` stay open. State stays with `gh pr ready` and
`gh pr merge`, denied bare and with arguments: draft→ready is the script's hand-over (#218),
merge is the maintainer's. The maintainer's own sessions lose both through Claude.

**The branch-protection gate: by path where the path is the target, by verb elsewhere.** PUT is
denied in all five pflag spellings (`-X PUT`, `-XPUT`, `-X=PUT`, `--method PUT`,
`--method=PUT`) because that is the write `.github/rulesets/README.md` documents and it is a
write to any endpoint. The rulesets endpoint itself is denied by path (`gh api *rulesets*`):
DELETE removes the gate outright, the path has one spelling where the method has five, and the
rule therefore also holds for a lowercase or shell-quoted method there, which the verb rules do
not (#231). The GET readback README names goes with it; the pipeline never reads rulesets and
the maintainer runs it in a terminal. `gh api graphql *RepositoryRuleset*` covers the mutations
without denying `gh api graphql` wholesale, which resolving review threads needs. `gh repo edit`
is denied entirely: README § Known gaps records that the repo-level `allow_merge_commit` flag
alone holds linear history, and nothing in the pipeline edits repository settings.

**The allow list is read-only or gated elsewhere.** `./gradlew`, `gh pr view`, `gh pr diff`,
`gh issue view`, `git status`, `git log`, `git diff`. A never-trusted clone ignores it while
still applying `deny` (#219).

`mcp__idea__rename_refactoring` predates #212; `CLAUDE.md` § IDE Tooling owns its reason.

## Consequences

These rules are a speed bump, not a boundary: a Bash pattern does not see `sh -c '...'`,
`/usr/bin/gh` or `git -C . push`. The boundary is the sandbox in the runner-only settings file
(#215).

A rule change is verified the way #212 was: a `claude -p --output-format json` run in a
remote-less `git init` scratch directory carrying the file, with
`--allowedTools "Bash(git *),Bash(gh *)"` so a missed deny executes harmlessly, and
`permission_denials[].tool_input.command` as the assertion. Run it red against the unchanged
file first; a model refusing on its own judgment is not a harness denial.
