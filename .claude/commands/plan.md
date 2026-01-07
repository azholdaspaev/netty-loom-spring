---
description: Create an architecture plan for the feature. Use after PRD is ready to design the technical solution.
allowed-tools: Read, Grep, Glob, Bash, Task
---

# /plan Command - Architecture Planning

Create a detailed architecture and implementation plan.

## Purpose
Design technical architecture and create implementation roadmap.

## Prerequisites
- `PRD_READY` state minimum
- `PRD.md` exists

## Arguments
Optional: Specific artifact folder
$ARGUMENTS

## Quality Gate Check
Before proceeding, verify:
1. Find the most recent artifact folder (or use specified folder)
2. Read `STATE.md` and verify state is `PRD_READY` or later
3. Verify `PRD.md` exists and contains required sections

**If prerequisites fail, output:**
```
QUALITY GATE FAILED: /plan requires PRD_READY state.
Current state: {current_state}
Missing: {what is missing}
Action: Please run /idea first to create a PRD.
```

## Process

### Step 1: Locate Artifact Folder
Find most recent or specified: `.artifacts/{date}-{feature-name}/`

### Step 2: Gather Context
Read the following:
- `PRD.md` - Feature requirements
- `RESEARCH.md` - Technical research (if exists)
- `conventions.md` - Project standards

### Step 3: Invoke Planner Agent
Delegate to planner subagent to:
- Review PRD and research
- Analyze existing architecture
- Design solution following established patterns
- Create detailed implementation plan

### Step 4: Create Architecture Document
Save to: `.artifacts/{date}-{feature-name}/ARCHITECTURE.md`

Use the template from the planner agent.

### Step 5: Update Workflow State
Update `STATE.md`:
- Set state to: `PLAN_APPROVED`
- Add entry to state history

## Output Artifacts
- `ARCHITECTURE.md` - Architecture plan

## Quality Criteria
- [ ] Architecture aligns with existing patterns
- [ ] Component responsibilities clearly defined
- [ ] Interfaces well-specified
- [ ] Implementation phases defined
- [ ] Security considerations addressed

## Next Steps
- `/tasks` - Generate implementation task list

## Example Usage
```
/plan
/plan 2024-01-07-user-auth
```
