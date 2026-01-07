---
description: Start a new feature by capturing the idea and creating a PRD. Use this command to begin the AIDD workflow with requirements analysis.
allowed-tools: Read, Grep, Glob, Bash, WebFetch, Task
---

# /idea Command - Feature Ideation and PRD Creation

You are initiating the AIDD workflow for a new feature.

## Purpose
Transform a feature idea into a structured Product Requirements Document (PRD).

## Prerequisites
- None (this is the starting command)

## Arguments
Feature description: $ARGUMENTS

## Process

### Step 1: Create Artifact Folder
Create the artifact folder with today's date and feature name:
```
.artifacts/{YYYY-MM-DD}-{feature-name-kebab-case}/
```

### Step 2: Initialize STATE.md
Create STATE.md in the artifact folder:
```markdown
# Workflow State: {Feature Name}

## Current State
**State:** INITIAL
**Updated:** {timestamp}

## State History
| State | Entered | Notes |
|-------|---------|-------|
| INITIAL | {timestamp} | Workflow started |
```

### Step 3: Invoke Analyst Agent
Delegate to the analyst subagent to:
- Gather requirements from the provided feature description
- Ask clarifying questions if needed
- Analyze existing codebase for context
- Create comprehensive PRD

### Step 4: Create PRD Document
Save PRD to: `.artifacts/{date}-{feature-name}/PRD.md`

Use the template from the analyst agent.

### Step 5: Update Workflow State
Update STATE.md:
- Set state to: `PRD_READY`
- Add entry to state history

## Output Artifacts
- `STATE.md` - Workflow state tracker
- `PRD.md` - Product Requirements Document

## Quality Gate
Before completing, verify:
- [ ] PRD has all required sections
- [ ] User stories are well-defined
- [ ] Acceptance criteria are specific
- [ ] Scope is clearly bounded

## Next Steps
After PRD is complete, suggest:
- `/researcher` - To investigate technical approaches (optional)
- `/plan` - To create architecture plan (if research not needed)

## Example Usage
```
/idea Add user authentication with OAuth2 support
/idea Create a dashboard to display usage metrics
/idea Implement file upload with drag-and-drop
```
