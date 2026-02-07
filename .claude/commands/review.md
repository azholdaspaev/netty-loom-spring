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

- Implementation started (some tasks completed in `docs/{task-name}/tasks.md`)

## Workflow

1. **Invoke the reviewer agent** to:
   - Read completed tasks and architecture plan
   - Find all changed/new files
   - Run `./gradlew check` and `./gradlew spotlessCheck`
   - Apply library-specific checklist:
     - Thread safety (no EventLoop blocking, `ReentrantLock`, virtual threads)
     - ByteBuf lifecycle (retain/release, leak detection)
     - Module boundaries (core has no Spring, ArchUnit)
     - API design (records, sealed interfaces, annotations)
     - Code quality (Spotless, naming, Javadoc)
     - Testing (JUnit 5, AssertJ, coverage)

2. **Categorize findings:**
   - **CRITICAL** — Thread-safety bugs, resource leaks, boundary violations
   - **WARNING** — Missing tests, incomplete Javadoc
   - **SUGGESTION** — Alternative patterns

3. **Output:** `docs/{task-name}/review.md`

4. **Gate:**
   - `REVIEW_OK` — No critical issues
   - `REVIEW_BLOCKED` — Critical issues found, must fix

## If Blocked

1. Fix all CRITICAL issues
2. Re-run `/review {task-name}`
3. Continue when `REVIEW_OK`

## Next Step

After review is approved, run `/validate {task-name}` to verify all gates pass.
