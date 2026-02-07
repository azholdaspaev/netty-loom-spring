---
description: Design technical architecture for the feature
---

# /plan - Architecture Design

Design the technical architecture and create an implementation plan.

## Usage

```
/plan {task-name}
```

**Arguments:**
- `$TASK_NAME` - Task identifier (must match existing task folder)

## Prerequisites

- Spec exists at `docs/{task-name}/spec.md` with `SPEC_READY`
- Research exists at `docs/{task-name}/research.md` with `RESEARCH_OK`

## Workflow

1. **Read all inputs**:
   - Spec for API contract and requirements
   - Research for patterns and constraints

2. **Invoke the architect agent** to:
   - Design class hierarchy (classes, interfaces, records, sealed types)
   - Define thread model per public method
   - Verify module placement against ArchUnit rules
   - Design ByteBuf lifecycle (if applicable)
   - Plan configuration properties (if applicable)
   - List all files to create/modify with absolute paths
   - Document trade-off decisions

3. **Output:** `docs/{task-name}/plan.md`

4. **Gate:** `PLAN_OK` when complete

## Next Step

After plan is approved, run `/tasks {task-name}` to decompose into implementable tasks.
