---
description: Conduct code review of all implementation changes. Use after all implementation tasks are complete.
allowed-tools: Read, Grep, Glob, Bash, Task
---

# /review Command - Code Review

Conduct comprehensive code review of implementation.

## Purpose
Review all code changes for quality, correctness, and convention compliance.

## Prerequisites
- `IMPLEMENT_STEP_OK` state
- All tasks in `TASKS.md` are `COMPLETED`

## Arguments
Optional: Specific artifact folder
$ARGUMENTS

## Quality Gate Check
Before proceeding, verify:
1. Find the most recent artifact folder (or use specified folder)
2. Read `STATE.md` and verify state is `IMPLEMENT_STEP_OK`
3. Read `TASKS.md` and verify all tasks are `COMPLETED`

**If prerequisites fail, output:**
```
QUALITY GATE FAILED: /review requires IMPLEMENT_STEP_OK state.
Current state: {current_state}
Incomplete tasks: {list of incomplete tasks}
Action: Complete all implementation tasks first.
```

## Process

### Step 1: Locate Artifact Folder
Find most recent or specified: `.artifacts/{date}-{feature-name}/`

### Step 2: Gather Changes
Read `IMPLEMENTATION_LOG.md` to identify:
- All files that were created or modified
- All tests that were added

### Step 3: Invoke Reviewer Agent
Delegate to reviewer subagent to:
- Review each changed file
- Check convention compliance
- Identify issues
- Provide recommendations

### Step 4: Create Review Report
Save to: `.artifacts/{date}-{feature-name}/REVIEW.md`

Use the template from the reviewer agent.

### Step 5: Handle Verdict

**If APPROVED:**
- Update `STATE.md` to `REVIEW_OK`
- Proceed to next step

**If REQUEST_CHANGES:**
- Keep state as `IMPLEMENT_STEP_OK`
- List all issues that must be fixed
- Instruct to fix issues and run `/review` again

## Output Artifacts
- `REVIEW.md` - Code review report

## Pass/Fail Criteria

### Automatic FAIL (REQUEST_CHANGES)
- Any Critical severity issue found
- More than 3 Warning severity issues found
- Security vulnerability identified
- Test coverage below 50% for new code

### Issue Severity Definitions

**Critical (blocks approval):**
- Security vulnerabilities (injection, XSS, auth bypass)
- Data loss potential
- Breaking existing functionality
- Missing error handling on external calls

**Warning (should fix):**
- Code duplication >10 lines
- Missing input validation
- Performance concern (N+1 queries, unbounded loops)
- Convention violations

**Suggestion (optional):**
- Naming improvements
- Code organization
- Additional tests
- Documentation enhancements

## Quality Checklist
- [ ] Every modified file has been read
- [ ] Security checklist completed
- [ ] Performance implications assessed
- [ ] Convention compliance verified
- [ ] Clear verdict provided (APPROVED or REQUEST_CHANGES)

## Decision Points - STOP and Clarify If:
- Issue severity is borderline between levels (e.g., Warning vs Critical)
- Code works but doesn't match architecture - is deviation acceptable?
- Convention violation is intentional for good reason - approve or reject?
- Security concern exists but fix significantly increases complexity
- Review finds PRD/Architecture gaps - proceed or loop back?
- Third-party dependency has known vulnerabilities but no alternative

## Handling Review Failures

When review returns `REQUEST_CHANGES`:
1. Read the issues from `REVIEW.md`
2. Fix each critical issue
3. Optionally fix warnings and suggestions
4. Run `/review` again

## Next Steps
After code review, display:

**Artifact Folder:** `.artifacts/{date}-{feature-name}/`

**Related Documents:**
- PRD: `.artifacts/{date}-{feature-name}/PRD.md`
- Architecture: `.artifacts/{date}-{feature-name}/ARCHITECTURE.md`
- Tasks: `.artifacts/{date}-{feature-name}/TASKS.md`
- Implementation Log: `.artifacts/{date}-{feature-name}/IMPLEMENTATION_LOG.md`
- Review: `.artifacts/{date}-{feature-name}/REVIEW.md`

**Suggested Commands:**
- If `APPROVED`: `/qa .artifacts/{date}-{feature-name}/` - Run quality assurance
- If `REQUEST_CHANGES`: Fix issues and run `/review .artifacts/{date}-{feature-name}/` again

## Example Usage
```
/review
/review 2024-01-07-user-auth
```
