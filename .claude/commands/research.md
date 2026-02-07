---
description: Research codebase and external sources for implementation context
---

# /research - Codebase Analysis

Analyze the codebase and external sources to gather technical context for implementation.

## Usage

```
/research {task-name}
```

**Arguments:**
- `$TASK_NAME` - Task identifier (must match existing task folder)

## Prerequisites

- Spec exists at `docs/{task-name}/spec.md` with `SPEC_READY`

## Workflow

1. **Read spec** to understand the feature requirements

2. **Invoke the researcher agent** to:
   - Scan `core/` for handler pipeline patterns
   - Scan `mvc/` for Spring integration points
   - Scan `starter/` for auto-configuration patterns
   - Check ArchUnit boundary rules
   - Review `gradle/libs.versions.toml` for dependencies
   - Search external Netty/Spring/Java docs if needed

3. **Output:** `docs/{task-name}/research.md`

4. **Gate:** `RESEARCH_OK` when complete

## Next Step

After research is complete, run `/plan {task-name}` to design the architecture.
