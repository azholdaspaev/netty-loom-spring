---
name: rfc-revise
description: Synthesize review feedback into a prioritized revision brief. Deduplicates findings, highlights consensus issues, and recommends next action.
context: fork
allowed-tools: Read Write Glob Grep AskUserQuestion
---

# RFC Revision Synthesizer

You synthesize feedback from 4 independent reviewers into a single, actionable revision brief.

## Input

The RFC directory path is provided as the first argument: `$ARGUMENTS`

Read all files in `<RFC_DIR>/reviews/`:
- `architecture.md`
- `security.md`
- `user-experience.md`
- `testability-operability.md`

Also read:
- The RFC itself (`RFC-*.md`) for context
- `requirements.md` — to cross-reference review feedback against original goals and non-goals. If a reviewer requests something that was explicitly a non-goal, do NOT include it as an action item — flag it in the "Feedback vs Non-Goals Conflicts" section instead.

## Process

### 1. Deduplicate

Multiple reviewers often flag the same underlying issue from different angles. Group related concerns together and identify the root issue.

### 2. Identify Consensus

Flag issues raised by 2 or more reviewers — these are high-confidence problems that must be addressed.

### 3. Check Against Non-Goals

For each reviewer concern, check whether it conflicts with an explicit non-goal from requirements.md. When a reviewer's concern directly contradicts an explicit non-goal, separate it into the "Feedback vs Non-Goals Conflicts" section rather than treating it as an action item. Present these conflicts to the user before asking for their decision, so they can consciously choose to either uphold the non-goal or reconsider it.

### 4. Prioritize

Rate each unique issue:
- **Critical** — blocks acceptance, must be resolved
- **High** — significant gap, should be resolved before acceptance
- **Medium** — valid concern, worth addressing but not blocking
- **Low** — suggestion for improvement, author's discretion

### 5. Generate Action Items

For each issue, produce a specific, actionable recommendation at the decision level. Not "improve the design" but "the RFC proposes a single module for both HTTP/1.1 and HTTP/2 handling — split into separate modules because they have different lifecycle requirements and can evolve independently."

### 6. Overall Recommendation

Based on the aggregate findings, recommend one of:
- **Accept as-is** — no critical or high issues, the RFC is ready
- **Minor revision** — a few targeted fixes needed, no structural changes
- **Major revision** — significant gaps in design, security, or problem-solving that require rethinking parts of the proposal

## Output

Write `<RFC_DIR>/revision-brief.md` with this structure:

```markdown
# Revision Brief: [RFC Title]

## Overall Recommendation: [Accept as-is | Minor revision | Major revision]

## Review Summary

| Reviewer | Verdict | Critical | High | Medium | Low |
|----------|---------|----------|------|--------|-----|
| Architecture | [verdict] | N | N | N | N |
| Security | [verdict] | N | N | N | N |
| User Experience | [verdict] | N | N | N | N |
| Testability & Operability | [verdict] | N | N | N | N |

## Feedback vs Non-Goals Conflicts

[If any reviewer feedback contradicts explicit non-goals from requirements.md, list them here:]
- **[Reviewer]** suggested [X], but this was explicitly listed as a non-goal: "[exact non-goal text]". No action required unless the user wants to revisit the non-goal.

[If no conflicts, write: "No conflicts between reviewer feedback and stated non-goals."]

## Consensus Issues (raised by 2+ reviewers)

### [Issue Title]
- **Severity:** [Critical/High/Medium/Low]
- **Raised by:** [Reviewer 1, Reviewer 2, ...]
- **Description:** [What the issue is]
- **Action:** [Specific fix]

## All Action Items (prioritized)

### Critical
1. [Action item with specific guidance]

### High
1. [Action item with specific guidance]

### Medium
1. [Action item with specific guidance]

### Low
1. [Action item with specific guidance]

## Reviewer Verdicts
- Architecture: [APPROVE/REQUEST CHANGES/NEEDS DISCUSSION] — [summary]
- Security: [verdict] — [summary]
- User Experience: [verdict] — [summary]
- Testability & Operability: [verdict] — [summary]
```

## After Writing

Present the revision brief summary to the user — including any non-goals conflicts — and ask them to choose:

1. **Accept** — the RFC is finalized as-is
2. **Revise all** — apply all action items and run another review round (remind them: max 2 revision rounds)
3. **Revise selectively** — let me pick which action items to apply (ask the user which items to include/exclude, then update revision-brief.md to mark excluded items as "SKIPPED — user decision" before passing to the writer)
4. **Add my own notes** — I have additional feedback beyond what reviewers found (collect user input via AskUserQuestion and append a "## Author's Notes" section to revision-brief.md with the user's additional direction)
5. **Abandon** — discard the RFC

Options 3 and 4 can be combined — if the user picks both, first collect their notes, then let them filter action items.

Use AskUserQuestion to get their decision.

## Max Rounds Edge Case

If this is the second revision round and the user still wants changes, offer:
1. **Accept current state** — finalize what we have
2. **Accept with noted caveats** — finalize but append a "## Known Limitations / Deferred Items" section to the RFC listing unresolved action items for future work
3. **Restart requirements** — the RFC scope may need fundamental rethinking
