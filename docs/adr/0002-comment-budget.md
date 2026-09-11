# ADR 0002 — Comment budget: default to none, and hold the budgets after the javadoc jar

- Status: Accepted
- Date: 2026-09-12
- Rule: `CLAUDE.md` § Guidelines, rule 5 (the rule itself; this record holds only its rationale)

## Context

The source code is the only artefact that executes, that the compiler checks and that the tests
bind. A comment is unverified prose sitting beside it: nothing fails when the two disagree, so
drift is silent and permanent. When code and comment conflict, the code is right and the comment
is a bug. Three consequences follow:

- A comment that describes what the code does adds no information the day it is written, because
  the code already says that, correctly and forever — and it becomes false later.
- A comment a later edit can silently falsify is a liability, not documentation. Counts, censuses,
  lists of call sites and "the only place that..." all decay unnoticed.
- The only thing worth a comment is what the source cannot state: a fact from outside this
  repository, or a choice the code cannot show because the rejected alternative is absent by
  definition.

Comment density across the repository was bimodal when the rule was written — past 50% of lines in
some files, zero in others. So a neighbouring file is not evidence of the norm, and the heaviest
files are a ceiling already set too high, never a target. That is why rule 5 overrides rule 3
(match existing style) for comment volume, and only for comment volume.

#30 started shipping a javadoc jar, so javadoc now has readers who are not looking at the source.

## Decision

- The default is no comment and no javadoc. A comment is written only when one of rule 5's closed
  list of triggers fires, within rule 5's line budgets.
- The budgets are deliberately unchanged by the javadoc jar. Widening them is a separate decision
  to be recorded here, not a consequence of #30.
- Class javadoc that would exceed 20 lines is a design document: it goes under `docs/adr/` with a
  one-line pointer left in the source.

## Consequences

- Reviewers quote the rule's triggers and budgets by number; a comment that fires no trigger, or
  exceeds a budget, is a finding.
- `#NN` issue citations and `// --- Name ---` section banners are outside the rule: neither is prose.
