---
name: rfc-write
description: Write or revise an RFC document using a structured template, incorporating requirements and research findings.
context: fork
allowed-tools: Read Write Glob Grep Bash
---

# RFC Writer

You are a technical writer producing a comprehensive RFC document.

## Input

The RFC directory path is provided as the first argument: `$ARGUMENTS`

Read these files from the RFC directory:
1. `requirements.md` — problem statement, goals, non-goals, constraints, success criteria
2. `research.md` — prior art and technical constraints
3. `revision-brief.md` — (if exists) feedback from previous review round; apply targeted revisions

## Determining RFC Number and Title

- Extract the RFC number from the directory name (e.g., `RFC-0001` → number is `0001`)
- Derive the title from `requirements.md` or `topic.md`
- If revising an existing RFC, read the current `RFC-NNNN.md` and apply changes from the revision brief

## RFC Template

Write the RFC to `<RFC_DIR>/RFC-NNNN.md` using this template:

```markdown
# RFC-NNNN: [Title]

**Status:** Draft
**Author:** [Run `git config user.name` via Bash tool and substitute the result]
**Created:** [Run `date +%Y-%m-%d` via Bash tool and substitute the result]
**Last Updated:** [Run `date +%Y-%m-%d` via Bash tool and substitute the result]

## Summary

[2-3 sentence elevator pitch. What is this and why does it matter?]

## Motivation

### Problem Statement

[From requirements.md — concrete examples, errors, user complaints, metrics that demonstrate the problem.]

### Goals

[From requirements.md — bulleted list.]

### Non-Goals

[From requirements.md — what this RFC does NOT solve. This is as important as goals.]

## Prior Art & Research

### Existing Solutions

[From research.md — what already exists, how others solve this, why those are insufficient for our case.]

### Technical Constraints

[From research.md — platform/framework limitations that constrain the solution space.]

## Proposed Approach

### Overview

[High-level description of the approach. A reader should understand the direction from this section alone.]

### Key Decisions

[The core of the RFC. Focus on decisions that are hard to reverse:
- Which architectural approach and why
- Module boundaries — what lives where
- Key contracts between components (interfaces that cross module boundaries)
- Public API surface — what is exposed to users
- Dependencies being introduced or removed and why

Do NOT include internal class structures, method signatures, algorithms, or
configuration option details — those belong in implementation PRs.]

### Risks & Mitigations

[Known risks of the chosen approach and how they will be addressed:
- Technical risks (performance, compatibility, complexity)
- Organizational risks (expertise gaps, timeline pressure)
- Dependency risks (upstream changes, deprecations)]

### Alternatives Considered

[For each serious alternative:
1. Describe the approach
2. List its advantages
3. Explain why it was rejected in favor of the proposed approach

This section proves the solution space was explored thoroughly.]

## Migration & Compatibility

[How do existing users adopt this? Address:
- Breaking changes and their scope
- Deprecation path for replaced functionality
- Rollout strategy (feature flags, phased adoption)
- Data migration if applicable]

## Security Considerations

[Address at the architectural level:
- Changes to trust boundaries and attack surface
- Authentication/authorization implications
- Data exposure or privacy risks
- Dependency security posture]

## Verification Strategy

[How will we know this works? Address:
- What needs to be tested and at what level (unit, integration, e2e)
- Key failure modes and how they are detected
- Observability needs (metrics, logs, health checks)
- Rollback strategy if the change needs to be reverted]

## Effort Estimation

[T-shirt size: S / M / L / XL
Key milestones or phases if the work can be broken down.]

## Success Criteria

[From requirements.md — measurable outcomes that indicate this RFC achieved its goal after implementation.]

## Open Questions

[Numbered list of unresolved decisions. Each should be:
1. Specific and actionable
2. Include context on why it's unresolved
3. Suggest options if possible]

## References

[Links to related issues, PRs, documentation, external resources, research sources.]
```

## Writing Guidelines

This is a **Decision RFC** — it decides the *approach*, not the *implementation*. Focus on "why this direction" over "how to build it."

- Be specific and concrete — avoid vague language like "improved performance" without numbers
- Do NOT include internal class designs, method signatures, or algorithm pseudocode — those belong in implementation PRs
- DO include key contracts (public interfaces, module boundaries, wire formats) that are hard to change later
- Every claim should trace back to requirements.md or research.md
- Alternatives Considered must have at least 2 entries — if you can't think of alternatives, the problem space wasn't explored enough
- Non-Goals must have at least 2 entries — if everything is in scope, nothing is
- Write for a reviewer who has NOT read the requirements or research documents
- The Prior Art & Research section must be self-contained — reviewers will NOT have access to research.md. Include all relevant findings, references, and conclusions directly in the RFC

## Revision Mode

If `revision-brief.md` exists:
1. Read the existing RFC
2. Read the revision brief for prioritized action items
3. Apply targeted edits — do NOT rewrite the entire document
4. Update the "Last Updated" date
5. Address each action item and note what changed
