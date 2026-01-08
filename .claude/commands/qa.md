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

## Pass/Fail Criteria

### Automatic FAIL
- Any test fails
- Critical bug found (crashes, data loss, security)
- Any acceptance criterion not verified
- Regression in existing functionality

### Bug Severity Definitions

**Critical (blocks release):**
- Application crash
- Data corruption/loss
- Security vulnerability
- Core functionality broken

**High (blocks release):**
- Feature doesn't work as specified
- Performance degradation >50%
- UI completely broken

**Medium (can release with plan):**
- Edge case failures
- Minor performance issues
- Cosmetic UI problems

**Low (can release):**
- Minor inconveniences
- Rare edge cases
- Polish items

## Quality Checklist
- [ ] All acceptance criteria tested with results documented
- [ ] Unit tests executed and pass
- [ ] Integration tests executed (if available)
- [ ] Manual verification of core flows
- [ ] Edge cases tested (empty input, invalid input, boundaries)

## Decision Points - STOP and Clarify If:
- Bug severity is borderline (Medium could be High in certain contexts)
- Test environment differs from production - results valid?
- Edge case behavior is undefined in PRD - what should happen?
- Performance testing needed but no baseline defined
- Flaky tests exist - retry count before FAIL?
- Partial functionality works - release with known issues or block?
- Acceptance criterion passes technically but UX feels wrong

## Handling QA Failures

When QA returns `FAIL`:
1. Read the bugs from `QA_REPORT.md`
2. Fix each bug
3. Run `/qa` again
4. If code changes are significant, may need `/review` again

## Next Steps
After QA testing, display:

**Artifact Folder:** `.artifacts/{date}-{feature-name}/`

**Related Documents:**
- PRD: `.artifacts/{date}-{feature-name}/PRD.md`
- Architecture: `.artifacts/{date}-{feature-name}/ARCHITECTURE.md`
- Tasks: `.artifacts/{date}-{feature-name}/TASKS.md`
- Review: `.artifacts/{date}-{feature-name}/REVIEW.md`
- QA Report: `.artifacts/{date}-{feature-name}/QA_REPORT.md`

**Suggested Commands:**
- If `PASS`: `/docs-update .artifacts/{date}-{feature-name}/` - Update documentation
- If `FAIL`: Fix issues and run `/qa .artifacts/{date}-{feature-name}/` again

## Example Usage
```
/qa
/qa 2024-01-07-user-auth
```
