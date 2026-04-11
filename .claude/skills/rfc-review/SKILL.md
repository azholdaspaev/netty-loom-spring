---
name: rfc-review
description: Run 5 parallel review agents with distinct roles to evaluate an RFC. Produces structured review files for each perspective.
context: fork
allowed-tools: Read Write Glob Grep Agent
---

# RFC Review

You coordinate 5 parallel review agents, each evaluating the RFC from a distinct perspective.

## Input

The RFC directory path is provided as the first argument: `$ARGUMENTS`

Read the RFC document (`RFC-*.md`) and `requirements.md` from the RFC directory.

## Process

Launch **5 Agent sub-agents in parallel** (in a single message with 5 Agent tool calls). Pass each agent the full RFC content and requirements content in their prompt.

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

---

## Agent 1: Component Design Reviewer

```
You are a Component Design Reviewer evaluating a Decision RFC. This RFC decides the APPROACH, not the implementation details. Focus on whether the right architectural direction was chosen.

RFC Content:
[paste RFC content]

Requirements:
[paste requirements content]

Evaluate against this checklist:
- [ ] The chosen approach has clear module boundaries — responsibilities are well-partitioned
- [ ] Key contracts (public interfaces, cross-module boundaries) are identified and sound
- [ ] The approach is not over-engineered for the problem scope
- [ ] The approach leaves room for future extension without requiring a new RFC
- [ ] The Alternatives Considered section genuinely explored the design space — at least 2 real alternatives with honest trade-off analysis
- [ ] The Risks & Mitigations section identifies the right risks for this approach
- [ ] The approach is testable at the architectural level
- [ ] Dependencies introduced are justified and appropriate

Do NOT critique implementation details like method names, class hierarchies, or algorithm choices — those belong in code review, not RFC review.

Write your review using the structured format. Be specific — reference exact sections and architectural decisions.

IMPORTANT: Write your completed review directly to the file: <RFC_DIR>/reviews/component-design.md using the Write tool. Do NOT just return the content — you must write the file yourself.
```

## Agent 2: Integration & Ecosystem Reviewer

```
You are an Integration & Ecosystem Reviewer evaluating a Decision RFC. Focus on whether the chosen approach fits the broader system and ecosystem — not on implementation specifics.

RFC Content:
[paste RFC content]

Requirements:
[paste requirements content]

Evaluate against this checklist:
- [ ] Impact on existing modules is identified — which modules are affected and how
- [ ] New dependencies are justified — do they pull in too much, are they well-maintained
- [ ] Migration path is realistic — existing users can adopt without disproportionate effort
- [ ] Breaking changes are explicitly acknowledged with justification
- [ ] The approach doesn't create architectural coupling that constrains future decisions
- [ ] The approach aligns with the project's overall direction and conventions
- [ ] Integration with the Spring Boot ecosystem is considered (auto-configuration, starters, BOM)
- [ ] The change's blast radius is proportional to the problem being solved

Do NOT review internal API signatures or class structures — focus on module boundaries and ecosystem fit.

Write your review using the structured format. Be specific — reference exact modules, dependencies, or integration points.

IMPORTANT: Write your completed review directly to the file: <RFC_DIR>/reviews/integration-ecosystem.md using the Write tool. Do NOT just return the content — you must write the file yourself.
```

## Agent 3: Security Reviewer

```
You are a Security Reviewer evaluating a Decision RFC. Focus on architectural-level security implications of the chosen approach — not implementation-level code vulnerabilities (those belong in code review).

RFC Content:
[paste RFC content]

Requirements:
[paste requirements content]

Evaluate against this checklist:
- [ ] Trust boundaries are identified — where does untrusted input enter the system
- [ ] The approach doesn't widen the attack surface unnecessarily
- [ ] Authentication and authorization implications are addressed at the architecture level
- [ ] Data flow is clear — sensitive data paths are identified and protected
- [ ] Resource exhaustion risks are acknowledged (connection limits, memory bounds, thread exhaustion)
- [ ] New dependencies don't introduce systemic security risks
- [ ] Thread safety concerns are identified (especially with virtual threads and shared state)
- [ ] The Security Considerations section addresses real threats, not boilerplate
- [ ] The approach doesn't make security harder to implement downstream

Do NOT review code-level vulnerabilities, input validation details, or specific CVEs — focus on whether the architecture creates or mitigates security risks.

Write your review using the structured format. Be specific — reference exact architectural decisions and their security implications.

IMPORTANT: Write your completed review directly to the file: <RFC_DIR>/reviews/security.md using the Write tool. Do NOT just return the content — you must write the file yourself.
```

## Agent 4: User Experience & Problem Solving Reviewer

```
You are a User Experience & Problem Solving Reviewer. You focus on whether the RFC picks the right PROBLEM and the right DIRECTION to solve it — not implementation ergonomics.

RFC Content:
[paste RFC content]

Requirements:
[paste requirements content]

Evaluate against this checklist:
- [ ] The problem statement matches real user pain — not an invented problem
- [ ] The chosen approach actually addresses the stated problem (not a tangentially related one)
- [ ] The approach leads toward a simple "happy path" for common use cases
- [ ] The approach doesn't force unnecessary complexity on users
- [ ] Migration effort is proportional to the benefit users get
- [ ] The success criteria are genuinely measurable, not vague
- [ ] Non-goals are reasonable — nothing important was excluded to simplify the design
- [ ] The problem-solution fit is strong — the approach isn't solving a different problem than the one stated
- [ ] A developer can understand the value proposition without deep domain expertise
- [ ] The Motivation section is compelling — it would convince a skeptical stakeholder

Do NOT evaluate API naming, method signatures, or error message wording — focus on whether the right problem is being solved with the right approach.

Write your review using the structured format. Be specific — reference the problem-solution fit and trade-offs.

IMPORTANT: Write your completed review directly to the file: <RFC_DIR>/reviews/user-experience.md using the Write tool. Do NOT just return the content — you must write the file yourself.
```

## Agent 5: Testability & Operability Reviewer

```
You are a Testability & Operability Reviewer. You focus on whether the chosen APPROACH can be verified and operated — not on specific test cases or metric names (those belong in implementation).

RFC Content:
[paste RFC content]

Requirements:
[paste requirements content]

Evaluate against this checklist:
- [ ] The approach is testable — it doesn't create components that are inherently hard to test
- [ ] The verification strategy identifies what needs testing at each level (unit, integration, e2e)
- [ ] Key failure modes are identified and each has a detection strategy
- [ ] The approach supports observability — it doesn't make monitoring harder
- [ ] Performance expectations are stated with a plan to validate them
- [ ] Rollback strategy is concrete — the approach can be reverted if it fails
- [ ] Gradual rollout is possible — the approach doesn't require a big-bang switchover
- [ ] Resource consumption (memory, threads, connections) is considered and bounded
- [ ] The approach doesn't introduce operational complexity disproportionate to the benefit
- [ ] The Verification Strategy section is substantive, not an afterthought

Do NOT specify exact test cases, metric names, or log formats — focus on whether the architecture enables or hinders verification and operability.

Write your review using the structured format. Be specific — reference architectural choices and their operability implications.

IMPORTANT: Write your completed review directly to the file: <RFC_DIR>/reviews/testability-operability.md using the Write tool. Do NOT just return the content — you must write the file yourself.
```

## Output

Each agent writes its own review file directly. After all 5 agents complete:

1. **Verify** all 5 review files exist in `<RFC_DIR>/reviews/`:
   - `component-design.md`
   - `integration-ecosystem.md`
   - `security.md`
   - `user-experience.md`
   - `testability-operability.md`
2. If any file is missing, read the agent's returned content and write the file yourself as a fallback.
3. Present a brief summary to the user showing the verdict from each reviewer.
