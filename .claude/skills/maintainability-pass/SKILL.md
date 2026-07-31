---
name: maintainability-pass
description: Second-pass pull request review applying the lenses defined in CLAUDE.md § "Code Review". Use after a bug-focused review pass has finished.
---

# Maintainability pass

The lenses for this pass live in exactly one place: the **Code Review** section of the
repository's root `CLAUDE.md`. Read that section now and apply the lenses it lists, with the
examples it gives.

Do not restate those lenses here, do not cache them, and do not add lenses of your own. This
pass enforces a rule against the same fact living in two files; a copy of the lens list in this
file would be the first violation of it.

## How to look

Read whole files and their siblings in the same package. Anything visible from the diff hunk
alone was the bug pass's job, not this one — the findings this pass exists for are the ones a
per-hunk scan structurally cannot see.

Scope findings to what the pull request adds or changes. A pre-existing inconsistency in an
untouched file is out of scope; mention it in the summary at most, never as an inline comment.

## What to post

One inline comment per finding, on the line it is about, via
`mcp__github_inline_comment__create_inline_comment` with `confirmed: true`.

Each comment must name the existing code it is comparing against — the sibling whose naming
sets the convention, the class that already owns the constant, the other file holding the same
fact. A maintainability finding with no cited precedent in this repository is taste, not a
finding: do not post it.

Prefer silence to a weak finding. This pass runs on top of a review that already posted; every
marginal comment spends a human's attention that the bug pass has first claim on.
