---
description: Review code changes for quality, thread safety, and compliance
---

# /review - Code Review

Review code changes for correctness, thread safety, resource lifecycle, and convention compliance.

## Usage

```
/review {task-name}
```

**Arguments:**
- `$TASK_NAME` - Task identifier (must match existing task folder)

## Prerequisites

- At least one completed task (`[x]`) in `docs/{task-name}/tasks.md` that has not yet been reviewed

## Workflow

1. **Identify the unreviewed task** — the most recently completed `[x]` item without a corresponding review section in `docs/{task-name}/review.md`

2. **Invoke the reviewer agent** scoped to that task's files:
   - Read the task description and architecture plan
   - Find changed/new files for this specific task
   - Run `./gradlew check` and `./gradlew spotlessCheck`
   - Apply library-specific checklist:
     - Thread safety (no EventLoop blocking, `ReentrantLock`, virtual threads)
     - ByteBuf lifecycle (retain/release, leak detection)
     - Module boundaries (core has no Spring, ArchUnit)
     - API design (records, sealed interfaces, annotations)
     - Code quality (Spotless, naming, Javadoc)
     - Testing (JUnit 5, AssertJ, coverage)

3. **Categorize findings:**
   - **CRITICAL** — Thread-safety bugs, resource leaks, boundary violations
   - **WARNING** — Missing tests, incomplete Javadoc
   - **SUGGESTION** — Alternative patterns

4. **Output:** **Append** to `docs/{task-name}/review.md` (do not overwrite previous task reviews)

5. **Gate:**
   - `REVIEW_OK` — No critical issues for this task
   - `REVIEW_BLOCKED` — Critical issues found, must fix

## If Blocked

1. Fix all CRITICAL issues
2. Re-run `/review {task-name}`
3. Continue when `REVIEW_OK`

## Next Step

- **REVIEW_BLOCKED** → Fix the CRITICAL issues, then re-run `/review {task-name}`.
- **REVIEW_OK + tasks remain** → Run `/implement {task-name}` to implement the next task.
- **REVIEW_OK + all tasks done** → All tasks implemented and reviewed. Run `/validate {task-name}` to verify all gates.
