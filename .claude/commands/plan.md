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

## Quality Checklist

### System Context (Required)
- [ ] Diagram or description showing where feature fits in existing system
- [ ] Integration points with existing components identified
- [ ] Data flow direction indicated

### Component Design (Required)
- [ ] Each new component has a single, stated responsibility
- [ ] Component interfaces defined (inputs, outputs, methods)
- [ ] No component has more than 3 direct dependencies

### Data Model (If applicable)
- [ ] New entities have defined fields and types
- [ ] Relationships between entities documented
- [ ] Database migrations identified if needed

### API Design (If applicable)
- [ ] Endpoints/functions listed with HTTP method and path
- [ ] Request/response schemas defined
- [ ] Error responses documented

### Implementation Phases (Required)
- [ ] Work broken into 2-6 sequential phases
- [ ] Each phase produces testable increment
- [ ] Phase order accounts for dependencies

### Security (Required for user-facing features)
- [ ] Authentication requirements stated
- [ ] Authorization/permissions model defined
- [ ] Data validation approach specified

### Alignment Check
- [ ] Uses same patterns as similar features in codebase
- [ ] File/folder structure follows existing conventions
- [ ] Naming conventions match existing code

## Decision Points - STOP and Clarify If:
- Multiple architectural patterns could work (e.g., REST vs GraphQL, SQL vs NoSQL)
- Existing codebase has inconsistent patterns - which to follow?
- Feature requires breaking changes to existing APIs/interfaces
- Performance requirements are not specified but could influence design
- Security model choice affects UX (e.g., session vs token auth)
- Technology choice is not specified (e.g., which library for dates/validation?)
- Integration approach with third-party services is unclear

## Next Steps
- `/tasks` - Generate implementation task list

## Example Usage
```
/plan
/plan 2024-01-07-user-auth
```
