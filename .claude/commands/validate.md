---
description: Validate workflow state and quality gates. Use anytime to check current status and prerequisites for next steps.
allowed-tools: Read, Grep, Glob, Bash
---

# /validate Command - Workflow Validation

Validate current workflow state and quality gates.

## Purpose
Check workflow compliance and provide status report.

## Prerequisites
- Artifact folder exists (or will list available folders)

## Arguments
Optional: Specific artifact folder
$ARGUMENTS

## Process

### Step 1: Find Artifact Folder
If no argument provided:
- List all folders in `.artifacts/`
- Use the most recent one
- If no folders exist, report "No active features found"

If argument provided:
- Use specified folder

### Step 2: Invoke Validator Agent
Delegate to validator subagent to perform full validation.

### Step 3: Check Workflow State
Read `STATE.md` and determine current state.

### Step 4: Validate Artifacts
For each state, verify required artifacts:

| State | Required Artifacts |
|-------|-------------------|
| INITIAL | STATE.md |
| PRD_READY | STATE.md, PRD.md |
| PLAN_APPROVED | STATE.md, PRD.md, ARCHITECTURE.md |
| TASKLIST_READY | STATE.md, PRD.md, ARCHITECTURE.md, TASKS.md |
| IMPLEMENT_STEP_OK | All above + all tasks COMPLETED |
| REVIEW_OK | All above + REVIEW.md (APPROVED) |
| RELEASE_READY | All above + QA_REPORT.md (PASS) |
| DOCS_UPDATED | All above + DOCS_SUMMARY.md |

### Step 5: Generate Validation Report
Display report to user (not saved to file):

```markdown
# Workflow Validation Report

**Feature:** {feature-name}
**Folder:** .artifacts/{folder-name}/
**Current State:** {state}
**Validated:** {timestamp}

## Artifact Status

| Artifact | Status | Notes |
|----------|--------|-------|
| STATE.md | OK/MISSING | |
| PRD.md | OK/MISSING/INCOMPLETE | |
| RESEARCH.md | OK/OPTIONAL | |
| ARCHITECTURE.md | OK/MISSING/INCOMPLETE | |
| TASKS.md | OK/MISSING/INCOMPLETE | |
| IMPLEMENTATION_LOG.md | OK/OPTIONAL | |
| REVIEW.md | OK/MISSING | Verdict: {verdict} |
| QA_REPORT.md | OK/MISSING | Verdict: {verdict} |
| DOCS_SUMMARY.md | OK/MISSING | |

## Task Progress
- Total: {N}
- Completed: {N}
- In Progress: {N}
- Pending: {N}

## Quality Gate Status

| Gate | Status |
|------|--------|
| PRD Complete | PASS/FAIL |
| Plan Approved | PASS/FAIL |
| Tasks Ready | PASS/FAIL |
| Implementation Done | PASS/FAIL |
| Review Passed | PASS/FAIL |
| QA Passed | PASS/FAIL |
| Docs Updated | PASS/FAIL |

## Available Commands
Based on current state ({state}), you can run:
- `{command} .artifacts/{folder-name}/`: {description}

## Blocked Commands
- `{command}`: requires {state} (run `{prerequisite command} .artifacts/{folder-name}/` first)

## Recommendations
1. Run `{recommended-command} .artifacts/{folder-name}/`

## Artifact References
Based on current state, relevant documents:
- PRD: `.artifacts/{folder-name}/PRD.md`
- {other relevant artifacts based on current state}
```

## Output
- Validation report displayed to user
- Recommendations for next steps

## No State Change
This command does not modify workflow state.

## Example Usage
```
/validate
/validate 2024-01-07-user-auth
```
