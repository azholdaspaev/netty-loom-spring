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

2. **Find next uncompleted task** (`[ ]` item)

3. **Invoke the implementer agent** for TDD cycle:
   - Write failing test
   - Run test, confirm RED
   - Write minimum implementation
   - Run test, confirm GREEN
   - Refactor while green
   - Run `./gradlew check`
   - Mark task `[x]`

4. **Repeat** for each remaining task

5. **Gate:** `CODE_OK` when all tasks pass and `./gradlew check` succeeds

## Constraints

- One task at a time
- Tests must pass before proceeding
- Follow project conventions (see `.claude/rules/`)
- No skipping TDD steps
- Javadoc on all public API

## Next Step

After all implementation is complete, run `/review {task-name}` for code review.
