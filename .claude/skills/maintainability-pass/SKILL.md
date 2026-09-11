---
name: maintainability-pass
description: Second-pass pull request review for maintainability and consistency — naming, magic constants, duplication, comment budget, scope, module boundaries. Use after a bug-focused review pass has finished.
---

# Maintainability pass

`CLAUDE.md` § Code Review says what the bug pass discards; this file owns the lenses for the rest,
phrased as rules so that a violation can be quoted.

## Lenses

- **Naming consistency (convention-by-example).** New types should match the emergent naming of their siblings even when no written rule exists. Example: everything in `core.handler` is `Http`-prefixed (`HttpRequestHandler`, `HttpRequestDispatcher`, `HttpExceptionHandler`, `HttpConnectionMetadata`) — a new class there should carry the prefix.
- **Magic constants.** Flag bare literals that encode a named concept; prefer a named constant or an existing enum/util. Example: HTTP scheme names and their default ports come from `io.netty.handler.codec.http.HttpScheme` (`HttpScheme.HTTPS.toString()` → `"https"`, `HttpScheme.HTTPS.port()` → `443`) rather than hardcoded `"http"/"https"` or `80/443`. Sentinel values (e.g. `""`/`0` for an unknown address) should be named constants documenting their contract.
- **Duplication.** Two shapes, both in scope. *The same fact expressed twice* — one concept living in two places, the coupling a per-hunk scan misses; a scheme→port default living as strings in one class and as `80/443` in another is the *same* fact, so centralize it in one owner and have the other delegate. *The same logic expressed twice* — copy-pasted or near-identical blocks across classes or test fixtures, where a later fix to one will not reach the other.
- **Comment budget.** Judge against `CLAUDE.md` § Guidelines rule 5's triggers and budgets. A comment firing no listed trigger is a finding, and so is one over budget — name the number it breaks. In scope: class javadoc past 8 lines (20 with a cited external contract), javadoc on a `private` member past 2 lines, javadoc on a `@Test`, rationale restated in a file that does not own it, and a paragraph about code that does not exist. Two shapes to look for: *volume outrunning the decision being recorded*, and *prose the diff has just falsified* — a comment the change made wrong or incomplete, or one asserting a count, a sole call site or an exhaustive list that a later edit will silently break. Since the source is the only source of truth, a comment contradicted by the code beside it is a correctness finding, not a nitpick.
- **Simplicity and scope.** Judge against `CLAUDE.md` § Guidelines — (2) Simplicity First and (3) Surgical Changes. Speculative abstraction, configurability nobody asked for, error handling for impossible states, and changed lines that do not trace to the stated goal are findings here, not taste.
- **Module boundaries.** Judge against `CLAUDE.md` § Architecture: the `starter → mvc → core` dependency flow, `core` carrying no Spring dependency, and `HttpRequestDispatcher` as the seam. New coupling that crosses a layer or routes around a seam is a finding even when it compiles.

## Before you read

When the `idea` MCP server is available, run IntelliJ's inspections over the files the pull
request touches — `mcp__idea__lint_files`, first at `min_severity: "error"`, then at
`"warning"`. `CLAUDE.md` § "IDE Tooling" has the loader line and the caveats.

Treat the output as a candidate list, never as findings. Most of it is noise, and a lint hit
earns an inline comment only by surviving the lenses and citing precedent like anything else.
What it is good for is the class of issue a human reader's eye slides over: `@Incubating` API
use, JSpecify `@NullMarked` gaps, and idioms the language level has superseded.

Skip this step silently if the server is not connected. It is an aid to the pass, not a
precondition for it.

## How to look

Read whole files and their siblings in the same package. Anything visible from the diff hunk
alone was the bug pass's job, not this one — the findings this pass exists for are the ones a
per-hunk scan structurally cannot see.

Scope findings to what the pull request adds or changes. A pre-existing inconsistency in an
untouched file is out of scope; mention it in the summary at most, never as an inline comment.

## What to post

Inside `.github/workflows/claude-review.yml` — the only place
`mcp__github_inline_comment__create_inline_comment` exists — one inline comment per finding, on
the line it is about, with `confirmed: true`. Anywhere else, return the findings to the caller;
`/flow:review` anchors and posts them itself.

Each comment must name the existing code it is comparing against — the sibling whose naming
sets the convention, the class that already owns the constant, the other file holding the same
fact. A maintainability finding with no cited precedent in this repository is taste, not a
finding: do not post it.

Prefer silence to a weak finding: every marginal comment spends a human's attention that the bug
pass has first claim on.
