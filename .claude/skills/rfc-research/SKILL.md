---
name: rfc-research
description: Research prior art and technical constraints for an RFC. Launches 2 parallel sub-agents for prior art scanning and constraint discovery.
context: fork
allowed-tools: Read Write Glob Grep Agent WebSearch WebFetch
---

# RFC Research

You are a technical researcher. Your job is to gather external evidence and context to inform an RFC.

## Input

The RFC directory path is provided as the first argument: `$ARGUMENTS`

Read `<RFC_DIR>/requirements.md` to understand what problem is being solved.

## Process

Before launching agents, read `CLAUDE.md` at the project root to understand the project architecture, module structure, and technology stack. Summarize the key details (modules, dependency flow, core technologies, conventions) — you'll inject this summary into both agent prompts so they have project context.

Launch **2 parallel Agent sub-agents**:

### Agent 1: Prior Art Scanner

Launch this agent with `WebSearch` and `WebFetch` available. Prompt the agent with:

```
You are researching prior art for an RFC. Use the WebSearch tool to find information and WebFetch to read full articles, documentation pages, or GitHub READMEs when search snippets aren't enough.

Project context (from CLAUDE.md):
[paste your summary of the project architecture, modules, and technology stack]

Here are the full requirements:

[paste the FULL content of requirements.md]

Search the web for:
1. Existing libraries, frameworks, or tools that solve the same or similar problem
2. Blog posts, conference talks, or articles discussing this problem space
3. How other major projects in the same ecosystem have approached this — identify relevant projects from the requirements rather than assuming a fixed list
4. Academic papers or RFCs from standards bodies if relevant

If WebSearch returns poor or no results for a query, try alternative search terms (e.g., search for the underlying problem rather than a specific solution name). Also try WebFetch on known documentation URLs for the technologies mentioned in the requirements — official docs, GitHub repos, and framework guides often have relevant information that doesn't surface well in search snippets. If a topic has genuinely no prior art, state that explicitly rather than padding with tangentially related results.

For each finding, record:
- What it is and who made it
- How it approaches the problem
- Strengths and weaknesses relative to our requirements
- URL/reference

Write your findings as structured markdown. Return the full content.
```

### Agent 2: Constraint Discovery

Launch this agent with `WebSearch`, `WebFetch`, `Glob`, `Grep`, and `Read` available. Prompt the agent with:

```
You are researching technical constraints for an RFC. Use the WebSearch tool for web research, WebFetch to read full documentation pages and issue trackers, and Glob/Grep/Read tools to explore the codebase.

Project context (from CLAUDE.md):
[paste your summary of the project architecture, modules, and technology stack]

Here are the full requirements:

[paste the FULL content of requirements.md]

Research:
1. Known limitations or issues with the technologies involved (search the web)
2. Compatibility concerns (version requirements, breaking changes in dependencies)
3. Performance characteristics and benchmarks from similar implementations
4. Common pitfalls others have encountered solving similar problems
5. Explore the current codebase systematically:
   a. Read CLAUDE.md at the project root for architecture overview
   b. Identify which modules would be affected by the proposed change
   c. Find existing interfaces, SPIs, and extension points relevant to the RFC topic
   d. Check dependency declarations (build.gradle.kts files) for version constraints
   e. Look for existing tests that exercise the code paths the RFC would change
   Report specific file paths and code patterns you find — not just "the codebase uses X pattern."

For each constraint found, record:
- What the constraint is
- Why it matters for this RFC
- Source/evidence
- Potential mitigation if applicable

Write your findings as structured markdown. Return the full content.
```

## Merging Guidelines

When merging the two agents' outputs:
- If Agent 2 found codebase patterns that contradict Agent 1's prior art recommendations, note the conflict explicitly in "Implications for Design"
- Remove redundancy between agents (both may find the same constraints)
- Ensure every claim in "Implications for Design" traces to a specific finding above it

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
