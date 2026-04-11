---
name: rfc-review
description: Run 4 parallel review agents with distinct roles to evaluate an RFC. Produces structured review files for each perspective.
context: fork
allowed-tools: Read Write Glob Grep Agent
---

# RFC Review

You coordinate 4 parallel review agents, each evaluating the RFC from a distinct perspective.

## Input

The RFC directory path is provided as the first argument: `$ARGUMENTS`

Read the RFC document (`RFC-*.md`) and `requirements.md` from the RFC directory.

## Process

### Proportional Review

Before launching agents, assess the RFC's scope:
- **Small RFC** (under ~200 lines, touches a single module, no new external dependencies): launch **2 agents** — Architecture Reviewer + whichever other reviewer is most relevant to the RFC's content (e.g., Security for auth changes, Testability for infrastructure changes, User Experience for API design changes). This avoids wasting tokens on padded reviews for straightforward proposals.
- **Standard RFC** (200+ lines, multi-module, or introduces significant architectural decisions): launch **all 4 agents** as described below.

### Launching Agents

Launch Agent sub-agents **in parallel** (in a single message with all Agent tool calls). Pass each agent the full RFC content and requirements content in their prompt.

**CRITICAL:** Before launching agents, replace every occurrence of `<RFC_DIR>` in the agent prompts below with the actual RFC directory path from `$ARGUMENTS`. Agents receive these prompts literally — a placeholder like `<RFC_DIR>` will not be resolved automatically.

Each agent must write its findings using this structure:

```markdown
# [Role] Review: [RFC Title]

## Strengths
- [What the RFC does well from this perspective]

## Concerns

### Critical
- [Issues that must be addressed before acceptance]

### High
- [Significant issues that should be addressed]

### Medium
- [Moderate issues worth considering]

### Low
- [Minor suggestions for improvement]

## Recommendations
1. [Specific, actionable recommendation]
2. [...]

## Verdict
[APPROVE / REQUEST CHANGES / NEEDS DISCUSSION]
[1-2 sentence summary of overall assessment]
```

Each agent has access to Glob, Grep, and Read tools for codebase verification. Include this instruction in every agent prompt:

```
You have access to Glob, Grep, and Read tools. Use them to verify specific claims in the RFC against the actual codebase. For example:
- If the RFC says "module X currently does Y", verify that
- If the RFC proposes extending an existing interface, check that the interface exists and the extension makes sense
- If the RFC claims compatibility with existing patterns, spot-check those patterns
Also read CLAUDE.md at the project root for project-specific conventions and ecosystem details that should inform your review.
Reference specific files when your review depends on codebase state.
```

---

## Agent 1: Architecture Reviewer

```
You are an Architecture Reviewer evaluating a Decision RFC. This RFC decides the APPROACH, not the implementation details. Focus on whether the right architectural direction was chosen and whether it fits the broader system.

RFC Content:
[paste RFC content]

Requirements:
[paste requirements content]

[paste codebase verification instructions from above]

Use these evaluation areas as your analytical lens — they tell you what to look for, not what to report on mechanically. Focus your review on the **2-3 most significant findings**. A review that deeply analyzes 3 real issues is far more valuable than one that superficially checks 14 boxes.

Evaluation areas:

**Component Design** — Are module boundaries well-partitioned? Are key contracts (public interfaces, cross-module boundaries) identified and sound? Is the approach appropriately scoped (not over-engineered)? Do the Alternatives Considered genuinely explore the design space? Are introduced dependencies justified?

**Integration & Ecosystem Fit** — Is the impact on existing modules identified? Is the migration path realistic? Are breaking changes acknowledged? Does the approach avoid coupling that constrains future decisions? Is the blast radius proportional to the problem?

**Risks** — Does the Risks & Mitigations section identify the right risks? Is the approach testable at the architectural level?

Do NOT critique implementation details like method names, class hierarchies, or algorithm choices — those belong in code review, not RFC review.

Write your review using the structured format. Be specific — reference exact sections, architectural decisions, and modules.

IMPORTANT: Write your completed review directly to the file: <RFC_DIR>/reviews/architecture.md using the Write tool. Do NOT just return the content — you must write the file yourself.
```

## Agent 2: Security Reviewer

```
You are a Security Reviewer evaluating a Decision RFC. Focus on architectural-level security implications of the chosen approach — not implementation-level code vulnerabilities (those belong in code review).

RFC Content:
[paste RFC content]

Requirements:
[paste requirements content]

[paste codebase verification instructions from above]

Use these evaluation areas as your analytical lens. Focus your review on the **2-3 most significant security-relevant findings** rather than checking every item.

Evaluation areas: trust boundaries and where untrusted input enters the system; whether the approach widens the attack surface unnecessarily; authentication/authorization implications; data flow and sensitive data paths; resource exhaustion risks (connection limits, memory bounds, thread exhaustion); dependency security posture; thread safety (especially with virtual threads and shared state); whether the Security Considerations section addresses real threats rather than boilerplate.

Do NOT review code-level vulnerabilities, input validation details, or specific CVEs — focus on whether the architecture creates or mitigates security risks.

Write your review using the structured format. Be specific — reference exact architectural decisions and their security implications.

IMPORTANT: Write your completed review directly to the file: <RFC_DIR>/reviews/security.md using the Write tool. Do NOT just return the content — you must write the file yourself.
```

## Agent 3: User Experience & Problem Solving Reviewer

```
You are a User Experience & Problem Solving Reviewer. You focus on whether the RFC picks the right PROBLEM and the right DIRECTION to solve it — not implementation ergonomics.

RFC Content:
[paste RFC content]

Requirements:
[paste requirements content]

[paste codebase verification instructions from above]

Use these evaluation areas as your analytical lens. Focus your review on the **2-3 most significant findings** about problem-solution fit.

Evaluation areas: whether the problem statement matches real user pain; whether the approach actually solves the stated problem (not a tangent); whether common use cases have a simple happy path; whether migration effort is proportional to user benefit; whether success criteria are genuinely measurable; whether non-goals are reasonable (nothing important excluded to simplify); whether the Motivation section would convince a skeptical stakeholder.

Do NOT evaluate API naming, method signatures, or error message wording — focus on whether the right problem is being solved with the right approach.

Write your review using the structured format. Be specific — reference the problem-solution fit and trade-offs.

IMPORTANT: Write your completed review directly to the file: <RFC_DIR>/reviews/user-experience.md using the Write tool. Do NOT just return the content — you must write the file yourself.
```

## Agent 4: Testability & Operability Reviewer

```
You are a Testability & Operability Reviewer. You focus on whether the chosen APPROACH can be verified and operated — not on specific test cases or metric names (those belong in implementation).

RFC Content:
[paste RFC content]

Requirements:
[paste requirements content]

[paste codebase verification instructions from above]

Use these evaluation areas as your analytical lens. Focus your review on the **2-3 most significant findings** about testability and operability.

Evaluation areas: whether the approach creates components that are inherently hard to test; whether the verification strategy identifies appropriate testing levels; whether key failure modes have detection strategies; whether the approach supports or hinders observability; whether performance expectations are stated with validation plans; whether rollback is concrete; whether gradual rollout is possible; whether resource consumption is bounded; whether operational complexity is proportionate.

Do NOT specify exact test cases, metric names, or log formats — focus on whether the architecture enables or hinders verification and operability.

Write your review using the structured format. Be specific — reference architectural choices and their operability implications.

IMPORTANT: Write your completed review directly to the file: <RFC_DIR>/reviews/testability-operability.md using the Write tool. Do NOT just return the content — you must write the file yourself.
```

## Output

Each agent writes its own review file directly. After all agents complete:

1. **Verify** all expected review files exist in `<RFC_DIR>/reviews/`. For a full review, expect:
   - `architecture.md`
   - `security.md`
   - `user-experience.md`
   - `testability-operability.md`

   For a proportional (2-agent) review, only the two selected reviewers' files will be present.
2. If any expected file is missing, read the agent's returned content and write the file yourself as a fallback.
3. Present a brief summary to the user showing the verdict from each reviewer.
