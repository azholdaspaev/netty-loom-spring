---
name: rfc-revise
description: Synthesize review feedback into a prioritized revision brief. Deduplicates findings, highlights consensus issues, and recommends next action.
context: fork
allowed-tools: Read Write Glob Grep AskUserQuestion
---

# RFC Revision Synthesizer

You synthesize feedback from 5 independent reviewers into a single, actionable revision brief.

## Input

The RFC directory path is provided as the first argument: `$ARGUMENTS`

Read all files in `<RFC_DIR>/reviews/`:
- `component-design.md`
- `integration-ecosystem.md`
- `security.md`
- `user-experience.md`
- `testability-operability.md`

Also read:
- The RFC itself (`RFC-*.md`) for context
- `requirements.md` — to cross-reference review feedback against original goals and non-goals. If a reviewer requests something that was explicitly a non-goal, flag it rather than including it as an action item.

## Process

### 1. Deduplicate

Multiple reviewers often flag the same underlying issue from different angles. Group related concerns together and identify the root issue.

### 2. Identify Consensus

Flag issues raised by 2 or more reviewers — these are high-confidence problems that must be addressed.

### 3. Prioritize

Rate each unique issue:
- **Critical** — blocks acceptance, must be resolved
- **High** — significant gap, should be resolved before acceptance
- **Medium** — valid concern, worth addressing but not blocking
- **Low** — suggestion for improvement, author's discretion

### 4. Generate Action Items

For each issue, produce a specific, actionable recommendation at the decision level. Not "improve the design" but "the RFC proposes a single module for both HTTP/1.1 and HTTP/2 handling — split into separate modules because they have different lifecycle requirements and can evolve independently."

### 5. Overall Recommendation

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
| Component Design | [verdict] | N | N | N | N |
| Integration & Ecosystem | [verdict] | N | N | N | N |
| Security | [verdict] | N | N | N | N |
| User Experience | [verdict] | N | N | N | N |
| Testability & Operability | [verdict] | N | N | N | N |

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
- Component Design: [APPROVE/REQUEST CHANGES/NEEDS DISCUSSION] — [summary]
- Integration & Ecosystem: [verdict] — [summary]
- Security: [verdict] — [summary]
- User Experience: [verdict] — [summary]
- Testability & Operability: [verdict] — [summary]
```

## After Writing

Present the revision brief summary to the user and ask them to choose:

1. **Accept** — the RFC is finalized as-is
2. **Revise** — apply the action items and run another review round (remind them: max 2 revision rounds)
3. **Abandon** — discard the RFC

Use AskUserQuestion to get their decision.
