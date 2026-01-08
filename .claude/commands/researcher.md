---
description: Conduct technical research for a feature. Use after /idea to explore implementation approaches and gather technical context.
allowed-tools: Read, Grep, Glob, Bash, WebFetch, WebSearch, Task
---

# /researcher Command - Technical Research

Conduct technical research for the current feature.

## Purpose
Research technical approaches, analyze patterns, and provide recommendations.

## Prerequisites
- `PRD_READY` state or later
- Valid artifact folder exists
- `PRD.md` exists and is complete

## Arguments
Optional: Specific artifact folder or research focus
$ARGUMENTS

## Quality Gate Check
Before proceeding, verify:
1. Find the most recent artifact folder (or use specified folder)
2. Read `STATE.md` and verify state is `PRD_READY` or later
3. Verify `PRD.md` exists

**If prerequisites fail, output:**
```
QUALITY GATE FAILED: /researcher requires PRD_READY state.
Current state: {current_state}
Action: Please run /idea first to create a PRD.
```

## Process

### Step 1: Locate Artifact Folder
Find most recent or specified: `.artifacts/{date}-{feature-name}/`

### Step 2: Read PRD
Read `PRD.md` to understand:
- Feature requirements
- Research scope
- Technical questions to answer

### Step 3: Invoke Researcher Agent
Delegate to researcher subagent to:
- Review PRD for research scope
- Analyze existing codebase patterns
- Research external solutions
- Document findings and recommendations

### Step 4: Create Research Report
Save to: `.artifacts/{date}-{feature-name}/RESEARCH.md`

Use the template from the researcher agent.

### Step 5: Update State (Optional)
Research does not change the workflow state, but note completion:
- Add note to STATE.md that research is complete

## Output Artifacts
- `RESEARCH.md` - Technical research report

## Quality Criteria
- [ ] Multiple approaches explored
- [ ] Pros/cons documented for each
- [ ] Existing codebase patterns identified
- [ ] Clear recommendation provided
- [ ] Sources cited

## Next Steps
- `/plan` - Create architecture based on research

## Example Usage
```
/researcher
/researcher 2024-01-07-user-auth
/researcher focus on OAuth providers
```
