---
description: Create a technical specification for a library feature
---

# /spec - Technical Specification

Create a technical specification defining the public API contract, module placement, and thread-safety guarantees.

## Usage

```
/spec {task-name}
```

**Arguments:**
- `$TASK_NAME` - Task identifier (creates `docs/{task-name}/` folder)

## Workflow

1. **Create task folder** if it doesn't exist: `docs/{task-name}/`

2. **Invoke the spec-writer agent** to:
   - Research existing API patterns in the codebase
   - Define public API surface (classes, interfaces, methods)
   - Specify module placement (core/mvc/starter)
   - Document thread-safety contracts
   - Document ByteBuf lifecycle (if applicable)
   - Define configuration properties (if applicable)
   - Assess backward compatibility
   - Write acceptance criteria as test scenarios

3. **Output:** `docs/{task-name}/spec.md`

4. **Gate:** `SPEC_READY` when complete

## What the Spec Captures

- Public API surface with method signatures
- Module placement with boundary justification
- Thread-safety contract per public method
- ByteBuf lifecycle (who retains, who releases)
- Configuration properties (`netty.loom.*`)
- Semver impact
- Acceptance criteria as `shouldDoX_whenY` test scenarios

## Next Step

After spec is complete, run `/research {task-name}` for codebase analysis.
