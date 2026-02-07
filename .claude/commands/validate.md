---
description: Audit all quality gates for the feature
---

# /validate - Gate Validation

Audit all quality gates to verify the feature is ready for release.

## Usage

```
/validate {task-name}
```

**Arguments:**
- `$TASK_NAME` - Task identifier (must match existing task folder)

## Workflow

1. **Invoke the validator agent** to check all gates:

| Gate | Checks |
|------|--------|
| `SPEC_READY` | File exists, no `[TBD]`, API surface defined, module placement specified |
| `RESEARCH_OK` | File exists, status `RESEARCH_OK`, no unresolved questions |
| `PLAN_OK` | File exists, status `PLAN_OK`, no `[PENDING]` decisions |
| `TASKS_OK` | File exists, all tasks `[x]`, module-prefixed |
| `CODE_OK` | `./gradlew check` passes (compile + test + spotless) |
| `REVIEW_OK` | File exists, no CRITICAL issues, status `REVIEW_OK` |
| `DOCS_OK` | Public API has Javadoc, CHANGELOG.md updated |

2. **Output:** Console report with gate summary

## If Not Ready

- Address blocking issues
- Re-run the relevant stage command
- Run `/validate` again

## If All Gates Pass

- Create PR
- Merge to main
