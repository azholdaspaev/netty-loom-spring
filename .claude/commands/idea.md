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

## Quality Checklist
Before completing, verify:

### Problem Statement (Required)
- [ ] 2-4 sentences describing the problem being solved
- [ ] Identifies who experiences the problem
- [ ] Explains why current state is insufficient

### Goals (Required)
- [ ] 3-5 specific, measurable objectives
- [ ] Each goal has a success indicator (metric or outcome)
- [ ] Goals are achievable within reasonable scope

### User Stories (Required)
- [ ] Minimum 2 user stories for the feature
- [ ] Each follows format: "As a [role], I want [capability], so that [benefit]"
- [ ] Each story is independent and testable

### Acceptance Criteria (Required)
- [ ] Each user story has 2-5 acceptance criteria
- [ ] Criteria use testable language ("User can...", "System displays...", "API returns...")
- [ ] No criteria use vague terms ("fast", "user-friendly", "efficient")

### Scope Definition (Required)
- [ ] "In Scope" section lists specific deliverables
- [ ] "Out of Scope" section explicitly excludes related but deferred items
- [ ] Scope is bounded (not open-ended)

### Dependencies (If applicable)
- [ ] External capabilities needed (e.g., "needs email sending", "needs payment processing")
- [ ] Required permissions/access documented
- [ ] DO NOT specify libraries or technical implementations (that's for Architecture)

### Risks (If applicable)
- [ ] At least 1 risk identified for non-trivial features
- [ ] Each risk has an impact level (High/Medium/Low)
- [ ] Each risk has a mitigation strategy

## Decision Points - STOP and Clarify If:
- User's feature request can be interpreted in multiple ways
- Target user/persona is not clear from the request
- Success metrics could be measured differently (e.g., "improve performance" - by how much?)
- Scope boundary is unclear (feature touches multiple areas)
- Similar functionality exists that might conflict or duplicate
- External dependencies are uncertain (which API version? which service?)

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
