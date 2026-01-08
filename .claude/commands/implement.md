---
description: Implement a specific task from the task list. Specify task ID to implement. Example: /implement TASK-001
allowed-tools: Read, Grep, Glob, Bash, Edit, Write, LSP, Task
---

# /implement Command - Task Implementation

Implement a specific task from the task list.

## Purpose
Execute implementation of a specific task following the architecture plan.

## Prerequisites
- `TASKLIST_READY` state or later
- `TASKS.md` exists
- Task ID provided in arguments

## Arguments
Required: Task ID to implement
$ARGUMENTS (e.g., TASK-001)

## Quality Gate Check
Before proceeding, verify:
1. Find the most recent artifact folder
2. Read `STATE.md` and verify state is `TASKLIST_READY` or later
3. Verify `TASKS.md` exists
4. Verify task ID exists in `TASKS.md`
5. Verify task is not already `COMPLETED`

**If prerequisites fail, output appropriate error:**
```
QUALITY GATE FAILED: /implement requires TASKLIST_READY state.
Current state: {current_state}
Action: Please run /tasks first.
```
```
QUALITY GATE FAILED: Task {ID} not found in TASKS.md.
Available tasks: {list}
```
```
QUALITY GATE FAILED: Task {ID} is already COMPLETED.
Next task: {next task}
```

## Process

### Step 1: Parse Task ID
Extract task ID from: $ARGUMENTS

### Step 2: Load Context
Read the following:
- `TASKS.md` - Get task details
- `ARCHITECTURE.md` - Understand design context
- `conventions.md` - Get coding standards

### Step 3: Verify Dependencies
Check that all tasks this task depends on are `COMPLETED`.

**If dependencies not met:**
```
DEPENDENCY CHECK FAILED: Task {ID} depends on {dependencies}.
Incomplete dependencies: {list}
Action: Complete dependent tasks first.
```

### Step 4: Update Task Status
In `TASKS.md`, update task status to `IN_PROGRESS`.

### Step 5: Invoke Implementer Agent
Delegate to implementer subagent to:
- Review task requirements
- Check conventions.md
- Implement the solution
- Write tests
- Self-review

### Step 6: Create/Update Implementation Log
Create or append to: `.artifacts/{date}-{feature-name}/IMPLEMENTATION_LOG.md`

```markdown
## {Task ID}: {Title}
**Completed:** {timestamp}

### Changes Made
- `{file}`: {description}

### Tests Added
- `{test file}`: {description}

### Notes
{any relevant notes}
```

### Step 7: Update Task Status
In `TASKS.md`:
- Set task status to `COMPLETED`
- Add completion timestamp

### Step 8: Check All Tasks Complete
If all tasks are `COMPLETED`:
- Update `STATE.md` to `IMPLEMENT_STEP_OK`

## Output
- Code changes as specified in task
- Updated `TASKS.md` with completion status
- `IMPLEMENTATION_LOG.md` entry

## Quality Checklist (per task)

### Completion Criteria
- [ ] All files listed in task are created/modified
- [ ] Each acceptance criterion has been verified
- [ ] Code compiles/runs without errors

### Code Quality
- [ ] No hardcoded values that should be configurable
- [ ] Functions are <50 lines (or justified exception)
- [ ] No commented-out code blocks
- [ ] Error cases handled (not just happy path)

### Testing
- [ ] At least 1 test per public function/endpoint
- [ ] Tests cover both success and error cases
- [ ] All tests pass

### Documentation
- [ ] Public APIs have docstrings/comments
- [ ] Complex logic has inline comments
- [ ] README updated if new setup steps needed

## Decision Points - STOP and Clarify If:
- Acceptance criteria can be interpreted differently
- Implementation approach differs from architecture (even if working)
- conventions.md is missing or doesn't cover this scenario
- Multiple valid error handling strategies exist (fail fast vs graceful degradation)
- Third-party API has multiple versions/options - which to use?
- Test data requirements are unclear (mock vs real data)
- Performance trade-off exists (speed vs memory vs readability)

## Next Steps
After task implementation, display:

**Artifact Folder:** `.artifacts/{date}-{feature-name}/`

**Related Documents:**
- PRD: `.artifacts/{date}-{feature-name}/PRD.md`
- Architecture: `.artifacts/{date}-{feature-name}/ARCHITECTURE.md`
- Tasks: `.artifacts/{date}-{feature-name}/TASKS.md`
- Implementation Log: `.artifacts/{date}-{feature-name}/IMPLEMENTATION_LOG.md`

**Task Progress:**
- Completed: {N}/{total}
- Next task: TASK-XXX - {title}
- Scope: {brief description of next task}

**Suggested Commands:**
- `/implement TASK-XXX` - Implement next pending task
- `/review .artifacts/{date}-{feature-name}/` - When all tasks complete

## Example Usage
```
/implement TASK-001
/implement TASK-002
```
