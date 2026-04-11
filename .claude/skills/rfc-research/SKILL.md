---
name: rfc-research
description: Research prior art and technical constraints for an RFC. Launches 2 parallel sub-agents for prior art scanning and constraint discovery.
context: fork
allowed-tools: Read Write Glob Grep Agent WebSearch
---

# RFC Research

You are a technical researcher. Your job is to gather external evidence and context to inform an RFC.

## Input

The RFC directory path is provided as the first argument: `$ARGUMENTS`

Read `<RFC_DIR>/requirements.md` to understand what problem is being solved.

## Process

Launch **2 parallel Agent sub-agents**:

### Agent 1: Prior Art Scanner

Launch this agent with `WebSearch` available. Prompt the agent with:

```
You are researching prior art for an RFC. Use the WebSearch tool to find information. Here is the problem being solved:

[paste problem statement and goals from requirements.md]

Search the web for:
1. Existing libraries, frameworks, or tools that solve the same or similar problem
2. Blog posts, conference talks, or articles discussing this problem space
3. How other major projects (e.g., Spring, Quarkus, Micronaut, Vert.x, Helidon) have approached this
4. Academic papers or RFCs from standards bodies if relevant

For each finding, record:
- What it is and who made it
- How it approaches the problem
- Strengths and weaknesses relative to our requirements
- URL/reference

Write your findings as structured markdown. Return the full content.
```

### Agent 2: Constraint Discovery

Launch this agent with `WebSearch`, `Glob`, `Grep`, and `Read` available. Prompt the agent with:

```
You are researching technical constraints for an RFC. Use the WebSearch tool for web research and Glob/Grep/Read tools to explore the codebase. Here is the problem being solved:

[paste problem statement, goals, and constraints from requirements.md]

Research:
1. Known limitations or issues with the technologies involved (search the web)
2. Compatibility concerns (version requirements, breaking changes in dependencies)
3. Performance characteristics and benchmarks from similar implementations
4. Common pitfalls others have encountered solving similar problems
5. Explore the current codebase to understand existing patterns and constraints

For each constraint found, record:
- What the constraint is
- Why it matters for this RFC
- Source/evidence
- Potential mitigation if applicable

Write your findings as structured markdown. Return the full content.
```

## Output

After both agents complete, merge their findings into a single document and write it to `<RFC_DIR>/research.md` with this structure:

```markdown
# Research: [RFC Title]

## Prior Art

### [Solution/Project 1]
- **What:** [description]
- **Approach:** [how it solves the problem]
- **Strengths:** [relative to our requirements]
- **Weaknesses:** [relative to our requirements]
- **Reference:** [URL]

### [Solution/Project 2]
...

## Key Takeaways from Prior Art
- [Insight 1]
- [Insight 2]

## Technical Constraints

### [Constraint 1]
- **Impact:** [how it affects our RFC]
- **Evidence:** [source]
- **Mitigation:** [if applicable]

### [Constraint 2]
...

## Implications for Design
- [How these findings should shape the RFC's proposed design]
```
