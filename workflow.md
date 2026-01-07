# AIDD Workflow Documentation

## Overview

**AI-Driven Development (AIDD)** is a structured methodology for developing features using AI agents. This document describes the complete workflow process.

## Workflow Diagram

```
┌─────────────┐
│   INITIAL   │
└──────┬──────┘
       │ /idea
       ▼
┌─────────────┐
│  PRD_READY  │ ──── /researcher (optional)
└──────┬──────┘
       │ /plan
       ▼
┌──────────────┐
│PLAN_APPROVED │
└──────┬───────┘
       │ /tasks
       ▼
┌───────────────┐
│TASKLIST_READY │
└──────┬────────┘
       │ /implement (repeat for each task)
       ▼
┌──────────────────┐
│IMPLEMENT_STEP_OK │
└──────┬───────────┘
       │ /review
       ▼
┌─────────────┐
│  REVIEW_OK  │ ◄── REQUEST_CHANGES (fix and retry)
└──────┬──────┘
       │ /qa
       ▼
┌───────────────┐
│ RELEASE_READY │ ◄── FAIL (fix and retry)
└──────┬────────┘
       │ /docs-update
       ▼
┌──────────────┐
│ DOCS_UPDATED │
└──────────────┘
```

---

## Phase Details

### Phase 1: Ideation (PRD_READY)

**Command:** `/idea {feature description}`
**Agent:** Analyst
**Purpose:** Transform feature idea into structured requirements

**Process:**
1. User provides feature idea
2. Analyst agent gathers requirements
3. Analyst asks clarifying questions if needed
4. Analyst creates comprehensive PRD
5. PRD saved to artifacts folder
6. State set to `PRD_READY`

**Artifacts Produced:**
- `STATE.md` - Workflow state tracker
- `PRD.md` - Product Requirements Document

**Quality Gate:** PRD must have all required sections

---

### Phase 2: Research (Optional)

**Command:** `/researcher`
**Agent:** Researcher
**Purpose:** Investigate technical approaches

**Process:**
1. Researcher reviews PRD
2. Analyzes existing codebase for patterns
3. Researches external solutions
4. Documents findings and recommendations

**Artifacts Produced:**
- `RESEARCH.md` - Technical research report

**Note:** This phase is optional. Skip if technical approach is already clear.

---

### Phase 3: Planning (PLAN_APPROVED)

**Command:** `/plan`
**Agent:** Planner
**Purpose:** Design technical architecture

**Process:**
1. Planner reviews PRD and research
2. Analyzes existing architecture
3. Designs solution following patterns
4. Creates detailed implementation plan
5. State set to `PLAN_APPROVED`

**Artifacts Produced:**
- `ARCHITECTURE.md` - Architecture plan with diagrams

**Quality Gate:** Architecture must be complete and aligned with codebase

---

### Phase 4: Task Generation (TASKLIST_READY)

**Command:** `/tasks`
**Agent:** Planner
**Purpose:** Break down architecture into implementable tasks

**Process:**
1. Architecture analyzed for implementation steps
2. Tasks extracted with dependencies
3. Acceptance criteria defined for each task
4. Tasks ordered for implementation
5. State set to `TASKLIST_READY`

**Artifacts Produced:**
- `TASKS.md` - Implementation task list

**Quality Gate:** Each task must have clear acceptance criteria

---

### Phase 5: Implementation (IMPLEMENT_STEP_OK)

**Command:** `/implement TASK-XXX`
**Agent:** Implementer
**Purpose:** Implement each task

**Process:**
1. Implementer reviews task requirements
2. Checks conventions.md for standards
3. Implements solution incrementally
4. Writes tests alongside code
5. Updates task status in TASKS.md
6. When all tasks complete: State set to `IMPLEMENT_STEP_OK`

**Artifacts Produced:**
- Code changes
- `IMPLEMENTATION_LOG.md` - Record of changes

**Quality Gate:** All acceptance criteria must be met for each task

---

### Phase 6: Code Review (REVIEW_OK)

**Command:** `/review`
**Agent:** Reviewer
**Purpose:** Ensure code quality

**Process:**
1. Reviewer analyzes all changes
2. Checks convention compliance
3. Identifies issues by severity
4. Provides verdict: `APPROVED` or `REQUEST_CHANGES`
5. If APPROVED: State set to `REVIEW_OK`

**Artifacts Produced:**
- `REVIEW.md` - Code review report

**If REQUEST_CHANGES:**
- Fix identified issues
- Run `/review` again
- Repeat until APPROVED

---

### Phase 7: Quality Assurance (RELEASE_READY)

**Command:** `/qa`
**Agent:** QA
**Purpose:** Validate through testing

**Process:**
1. QA runs test suites
2. Verifies acceptance criteria
3. Tests edge cases
4. Provides verdict: `PASS` or `FAIL`
5. If PASS: State set to `RELEASE_READY`

**Artifacts Produced:**
- `QA_REPORT.md` - QA test report

**If FAIL:**
- Fix identified bugs
- Run `/qa` again (may need `/review` if significant changes)
- Repeat until PASS

---

### Phase 8: Documentation (DOCS_UPDATED)

**Command:** `/docs-update`
**Agent:** Tech Writer
**Purpose:** Update project documentation

**Process:**
1. Tech Writer reviews implementation
2. Identifies documentation needs
3. Updates relevant documentation
4. Creates changelog entry
5. State set to `DOCS_UPDATED`

**Artifacts Produced:**
- `DOCS_SUMMARY.md` - Documentation changes summary
- Updated project documentation

**Workflow Complete!**

---

## Quality Gates Summary

| Transition | Gate Requirements |
|------------|-------------------|
| → PRD_READY | PRD has all required sections |
| → PLAN_APPROVED | Architecture is complete and valid |
| → TASKLIST_READY | Tasks properly defined with acceptance criteria |
| → IMPLEMENT_STEP_OK | All tasks completed, tests pass |
| → REVIEW_OK | Review verdict is APPROVED |
| → RELEASE_READY | QA verdict is PASS |
| → DOCS_UPDATED | Documentation is updated |

---

## Automated Workflow

Use `/feature-development {idea}` to run the complete workflow automatically.

The orchestrator will:
1. Execute each phase in sequence
2. Validate quality gates between phases
3. Pause and report if a gate fails
4. Handle review/QA iterations automatically
5. Continue when gates pass

---

## Workflow Validation

Use `/validate` at any time to:
- Check current workflow state
- Verify artifact completeness
- See available commands
- Identify blocking issues

---

## Best Practices

### Do's
- Start new features with `/idea`
- Follow the workflow sequence
- Use `/validate` to check progress
- Fix forward when issues found
- Keep artifacts updated

### Don'ts
- Don't skip phases
- Don't manually modify STATE.md
- Don't start new features mid-workflow
- Don't ignore quality gate failures

---

## Troubleshooting

### "Quality Gate Failed"
Run `/validate` to see what's missing, then run the prerequisite command.

### "Task Not Found"
Check TASKS.md for correct task IDs. Run `/validate` to see task status.

### "Stuck in Review/QA Loop"
Read the report artifacts to understand issues. Fix root causes, not symptoms.

### "State Out of Sync"
In rare cases, manually check and fix STATE.md. Use `/validate` to verify.

---

## Artifact Reference

| Artifact | Created By | Contains |
|----------|------------|----------|
| STATE.md | /idea | Current workflow state |
| PRD.md | /idea | Requirements document |
| RESEARCH.md | /researcher | Technical research |
| ARCHITECTURE.md | /plan | Architecture design |
| TASKS.md | /tasks | Implementation tasks |
| IMPLEMENTATION_LOG.md | /implement | Change log |
| REVIEW.md | /review | Review report |
| QA_REPORT.md | /qa | Test results |
| DOCS_SUMMARY.md | /docs-update | Doc changes |
