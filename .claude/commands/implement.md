---
description: Implement next task from the tasklist using TDD
---

# /implement - Code Implementation

Implement tasks from the tasklist using TDD.

## Usage

```
/implement {task-name}
```

**Arguments:**
- `$TASK_NAME` - Task identifier (must match existing task folder)

## Prerequisites

- Tasks exist at `docs/{task-name}/tasks.md` with `TASKS_OK`

## Workflow

1. **Read tasklist** at `docs/{task-name}/tasks.md`

2. **Find next uncompleted task** (`[ ]` item). If no uncompleted tasks remain, report "All tasks complete" and **STOP**.

3. **Invoke the implementer agent** for ONE TDD cycle:
   - Write failing test
   - Run test, confirm RED
   - Write minimum implementation
   - Run test, confirm GREEN
   - Refactor while green
   - Run `./gradlew check`
   - Mark task `[x]`

4. **STOP** — report which task was completed and how many tasks remain.

5. **Gate:** `CODE_OK` only when ALL tasks are `[x]` and `./gradlew check` succeeds

## Constraints

- One task at a time
- Tests must pass before proceeding
- Follow project conventions (see `.claude/rules/`)
- No skipping TDD steps
- Javadoc on all public API

## Next Step

Run `/review {task-name}` to review the task you just completed.
After review passes, run `/implement {task-name}` again for the next task.
When all tasks are complete and reviewed, run `/validate {task-name}`.
