---
description: Break down the plan into implementable tasks
---

# /tasks - Work Decomposition

Break down the architecture plan into atomic, implementable tasks.

## Usage

```
/tasks {task-name}
```

**Arguments:**
- `$TASK_NAME` - Task identifier (must match existing task folder)

## Prerequisites

- Plan exists at `docs/{task-name}/plan.md` with `PLAN_OK`

## Workflow

1. **Read the architecture plan** at `docs/{task-name}/plan.md`

2. **Invoke the architect agent** (Task Decomposition Mode) to:
   - Break plan into atomic tasks with module prefixes
   - Order by dependency (core types → handlers → mvc adapters → starter config → tests)

3. **Task prefixes:**
   - `CORE-N` — netty-loom-spring-core
   - `MVC-N` — netty-loom-spring-mvc
   - `STARTER-N` — netty-loom-spring-boot-starter
   - `EXAMPLE-N` — example modules
   - `TEST-N` — cross-cutting test tasks

4. **Each task includes:**
   - Checkbox (`[ ]`) for tracking
   - Description of the work
   - Acceptance criteria

5. **Output:** `docs/{task-name}/tasks.md`

6. **Gate:** `TASKS_OK` when complete

## Task Format

```markdown
## Core Tasks

- [ ] CORE-1: Create sealed interface for request types
  - Define `NettyRequest` sealed interface in core module
  - **Acceptance:** Interface compiles, ArchUnit passes

- [ ] CORE-2: Implement virtual thread handler
  - Create handler that offloads to virtual thread executor
  - **Acceptance:** Test confirms non-blocking EventLoop

## MVC Tasks

- [ ] MVC-1: Create Spring request adapter
  - Bridge NettyRequest to Spring's HttpServletRequest
  - **Acceptance:** Spring controller receives request
```

## Next Step

After tasks are defined, run `/implement {task-name}` to start implementation.
