---
description: Run the complete feature development workflow from idea to documentation. Orchestrates all AIDD phases automatically.
allowed-tools: Read, Grep, Glob, Bash, Edit, Write, LSP, WebFetch, WebSearch, Task
---

# /feature-development Command - Full Workflow Orchestration

Execute the complete AIDD workflow from idea to documentation.

## Purpose
Orchestrate the entire feature development lifecycle in one command.

## Prerequisites
- Feature idea provided in arguments

## Arguments
Required: Feature description
$ARGUMENTS

## Process

### Step 1: Display Workflow Plan
```
=================================================
AIDD Full Workflow Starting
=================================================

Feature: {feature description}

Phases:
1. [ ] Idea Analysis      -> PRD_READY
2. [ ] Research           -> Research Complete (optional)
3. [ ] Planning           -> PLAN_APPROVED
4. [ ] Task Generation    -> TASKLIST_READY
5. [ ] Implementation     -> IMPLEMENT_STEP_OK
6. [ ] Code Review        -> REVIEW_OK
7. [ ] QA Testing         -> RELEASE_READY
8. [ ] Documentation      -> DOCS_UPDATED

=================================================
```

### Step 2: Execute Phase 1 - Idea
Run `/idea {feature description}`
- Create artifact folder
- Create PRD
- Reach state: `PRD_READY`

Update display: `1. [x] Idea Analysis -> PRD_READY`

### Step 3: Execute Phase 2 - Research (Optional)
Ask user: "Do you want to run technical research? (y/n)"
- If yes: Run `/researcher`
- If no: Skip to planning

Update display: `2. [x] Research -> Complete` or `2. [-] Research -> Skipped`

### Step 4: Execute Phase 3 - Planning
Run `/plan`
- Create architecture plan
- Reach state: `PLAN_APPROVED`

Update display: `3. [x] Planning -> PLAN_APPROVED`

### Step 5: Execute Phase 4 - Task Generation
Run `/tasks`
- Generate task list
- Reach state: `TASKLIST_READY`

Update display: `4. [x] Task Generation -> TASKLIST_READY`

### Step 6: Execute Phase 5 - Implementation
For each task in order:
```
Implementing: TASK-{N} of {total}
```
Run `/implement TASK-XXX`

Repeat until all tasks complete.
- Reach state: `IMPLEMENT_STEP_OK`

Update display: `5. [x] Implementation -> IMPLEMENT_STEP_OK`

### Step 7: Execute Phase 6 - Code Review
Run `/review`
- If `REQUEST_CHANGES`:
  - Display issues
  - Fix issues
  - Run `/review` again
- Repeat until `APPROVED`
- Reach state: `REVIEW_OK`

Update display: `6. [x] Code Review -> REVIEW_OK`

### Step 8: Execute Phase 7 - QA Testing
Run `/qa`
- If `FAIL`:
  - Display bugs
  - Fix bugs
  - Run `/qa` again
- Repeat until `PASS`
- Reach state: `RELEASE_READY`

Update display: `7. [x] QA Testing -> RELEASE_READY`

### Step 9: Execute Phase 8 - Documentation
Run `/docs-update`
- Update documentation
- Reach state: `DOCS_UPDATED`

Update display: `8. [x] Documentation -> DOCS_UPDATED`

### Step 10: Completion Report
```markdown
=================================================
AIDD Workflow Complete!
=================================================

Feature: {feature name}
Final State: DOCS_UPDATED

## Artifacts Generated
- .artifacts/{date}-{feature-name}/
  - STATE.md
  - PRD.md
  - RESEARCH.md (if created)
  - ARCHITECTURE.md
  - TASKS.md
  - IMPLEMENTATION_LOG.md
  - REVIEW.md
  - QA_REPORT.md
  - DOCS_SUMMARY.md

## Summary
- Tasks Completed: {N}
- Review Iterations: {N}
- QA Iterations: {N}

## Documentation Updated
- {list of updated files}

=================================================
Feature development complete!
=================================================
```

## Error Handling

If any phase fails critically:
1. Stop the workflow
2. Display error and current state
3. Suggest manual intervention
4. User can continue with individual commands

## Example Usage
```
/feature-development Add user authentication with OAuth2 support
/feature-development Create a dashboard for usage metrics
/feature-development Implement file upload with drag-and-drop
```
