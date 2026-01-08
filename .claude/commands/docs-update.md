---
description: Update documentation for the completed feature. Use after QA passes to complete the workflow.
allowed-tools: Read, Grep, Glob, Bash, Edit, Write, Task
---

# /docs-update Command - Documentation Update

Update project documentation for the completed feature.

## Purpose
Create and update documentation to reflect new feature.

## Prerequisites
- `RELEASE_READY` state
- `QA_REPORT.md` exists with `PASS` verdict

## Arguments
Optional: Specific artifact folder
$ARGUMENTS

## Quality Gate Check
Before proceeding, verify:
1. Find the most recent artifact folder (or use specified folder)
2. Read `STATE.md` and verify state is `RELEASE_READY`
3. Read `QA_REPORT.md` and verify verdict is `PASS`

**If prerequisites fail, output:**
```
QUALITY GATE FAILED: /docs-update requires RELEASE_READY state.
Current state: {current_state}
QA status: {PASS/FAIL/NOT_FOUND}
Action: Please complete /qa first with PASS verdict.
```

## Process

### Step 1: Locate Artifact Folder
Find most recent or specified: `.artifacts/{date}-{feature-name}/`

### Step 2: Gather Context
Read the following:
- `PRD.md` - Feature description
- `ARCHITECTURE.md` - Technical details
- `IMPLEMENTATION_LOG.md` - What was changed

### Step 3: Invoke Tech Writer Agent
Delegate to tech-writer subagent to:
- Review implementation
- Identify documentation needs
- Update relevant docs

### Step 4: Documentation Tasks
Potential updates:
- `README.md` - If feature affects getting started or usage
- API documentation - If new endpoints/functions
- User guides - If new user-facing features
- Changelog - Add entry for the release

### Step 5: Create Documentation Summary
Save to: `.artifacts/{date}-{feature-name}/DOCS_SUMMARY.md`

Use the template from the tech-writer agent.

### Step 6: Update Workflow State
Update `STATE.md`:
- Set state to: `DOCS_UPDATED`
- Add entry to state history

### Step 7: Display Completion
```markdown
=================================================
Feature Development Complete!
=================================================

Feature: {feature name}
Final State: DOCS_UPDATED

All workflow phases completed successfully.

Artifact Location:
.artifacts/{date}-{feature-name}/

Documentation Updated:
- {list of updated files}

The feature is ready for release!
=================================================
```

## Output Artifacts
- `DOCS_SUMMARY.md` - Documentation changes summary
- Updated project documentation files

## Quality Checklist

### Required Updates
- [ ] README mentions new feature (if user-visible)
- [ ] API documentation added for new endpoints
- [ ] CHANGELOG entry created

### API Documentation (if applicable)
- [ ] All new endpoints documented
- [ ] Request/response examples provided
- [ ] Error responses documented
- [ ] Authentication requirements stated

### User Documentation (if user-visible feature)
- [ ] Feature purpose explained
- [ ] Usage instructions provided
- [ ] Configuration options documented

### Changelog Entry
- [ ] Version/date included
- [ ] Changes categorized (Added/Changed/Fixed/Removed)
- [ ] Breaking changes clearly marked
- [ ] Migration steps provided if needed

## Decision Points - STOP and Clarify If:
- Documentation style/format is inconsistent across project
- Feature is internal-only vs user-facing - documentation scope unclear
- Breaking changes exist but migration path is complex
- Version numbering scheme is not established
- Multiple documentation locations exist (README, wiki, docs site)
- API documentation format is not specified (OpenAPI, JSDoc, etc.)

## Workflow Complete
After this command, the AIDD workflow is complete.

The feature has gone through:
1. Requirements analysis (PRD)
2. Technical research (optional)
3. Architecture planning
4. Task breakdown
5. Implementation
6. Code review
7. QA testing
8. Documentation

## Example Usage
```
/docs-update
/docs-update 2024-01-07-user-auth
```
