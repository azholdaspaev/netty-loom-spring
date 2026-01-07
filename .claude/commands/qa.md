---
description: Run quality assurance tests and validation. Use after code review is approved.
allowed-tools: Read, Grep, Glob, Bash, Task
---

# /qa Command - Quality Assurance

Execute comprehensive QA testing.

## Purpose
Validate implementation through testing and acceptance criteria verification.

## Prerequisites
- `REVIEW_OK` state
- `REVIEW.md` exists with `APPROVED` verdict

## Arguments
Optional: Specific artifact folder
$ARGUMENTS

## Quality Gate Check
Before proceeding, verify:
1. Find the most recent artifact folder (or use specified folder)
2. Read `STATE.md` and verify state is `REVIEW_OK`
3. Read `REVIEW.md` and verify verdict is `APPROVED`

**If prerequisites fail, output:**
```
QUALITY GATE FAILED: /qa requires REVIEW_OK state.
Current state: {current_state}
Review status: {APPROVED/REQUEST_CHANGES/NOT_FOUND}
Action: Please complete /review first with APPROVED verdict.
```

## Process

### Step 1: Locate Artifact Folder
Find most recent or specified: `.artifacts/{date}-{feature-name}/`

### Step 2: Gather Test Information
Read the following:
- `PRD.md` - Get acceptance criteria
- `TASKS.md` - Get task acceptance criteria
- Project config files - Identify test commands

### Step 3: Invoke QA Agent
Delegate to qa subagent to:
- Run test suites
- Verify acceptance criteria
- Test edge cases
- Document findings

### Step 4: Execute Tests
Run available tests:
- Unit tests
- Integration tests (if available)
- Linting/static analysis

### Step 5: Create QA Report
Save to: `.artifacts/{date}-{feature-name}/QA_REPORT.md`

Use the template from the qa agent.

### Step 6: Handle Verdict

**If PASS:**
- Update `STATE.md` to `RELEASE_READY`
- Proceed to documentation

**If FAIL:**
- Keep state as `REVIEW_OK`
- List all bugs and issues
- Instruct to fix issues and run `/qa` again

## Output Artifacts
- `QA_REPORT.md` - QA test report

## Quality Criteria
- [ ] All acceptance criteria tested
- [ ] Unit tests pass
- [ ] No critical bugs
- [ ] Edge cases validated
- [ ] Performance acceptable

## Handling QA Failures

When QA returns `FAIL`:
1. Read the bugs from `QA_REPORT.md`
2. Fix each bug
3. Run `/qa` again
4. If code changes are significant, may need `/review` again

## Next Steps
- If `PASS`: `/docs-update` - Update documentation
- If `FAIL`: Fix issues and run `/qa` again

## Example Usage
```
/qa
/qa 2024-01-07-user-auth
```
