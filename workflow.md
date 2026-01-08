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

**Note:** PRD focuses on requirements only (WHAT/WHO/WHY). Technical details (libraries, APIs, components, HOW) belong in the Architecture phase.

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

**Quality Checklist:**
- [ ] Problem Statement: 2-4 sentences describing the problem being solved
- [ ] Problem Statement: Identifies who experiences the problem
- [ ] Problem Statement: Explains why current state is insufficient
- [ ] Goals: 3-5 specific, measurable objectives
- [ ] Goals: Each has a success indicator (metric or outcome)
- [ ] User Stories: Minimum 2 user stories
- [ ] User Stories: Each follows "As a [role], I want [capability], so that [benefit]"
- [ ] Acceptance Criteria: Each user story has 2-5 criteria
- [ ] Acceptance Criteria: Use testable language ("User can...", "System displays...")
- [ ] Acceptance Criteria: No vague terms ("fast", "user-friendly", "efficient")
- [ ] Scope: "In Scope" lists specific deliverables
- [ ] Scope: "Out of Scope" explicitly excludes deferred items
- [ ] Dependencies: External services/APIs identified (if applicable)
- [ ] Risks: At least 1 risk for non-trivial features with mitigation

**Decision Points - STOP and Clarify If:**
- Feature request can be interpreted in multiple ways
- Target user/persona is not clear
- Success metrics could be measured differently
- Scope boundary is unclear (feature touches multiple areas)
- Similar functionality exists that might conflict
- External dependencies are uncertain

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

**Quality Checklist:**
- [ ] System Context: Diagram/description showing where feature fits in system
- [ ] System Context: Integration points with existing components identified
- [ ] System Context: Data flow direction indicated
- [ ] Components: Each new component has single, stated responsibility
- [ ] Components: Interfaces defined (inputs, outputs, methods)
- [ ] Components: No component has more than 3 direct dependencies
- [ ] Data Model: New entities have defined fields and types (if applicable)
- [ ] Data Model: Relationships documented (if applicable)
- [ ] API Design: Endpoints listed with method and path (if applicable)
- [ ] API Design: Request/response schemas defined (if applicable)
- [ ] Implementation Phases: 2-6 sequential phases
- [ ] Implementation Phases: Each produces testable increment
- [ ] Security: Authentication requirements stated (for user-facing)
- [ ] Security: Authorization model defined (for user-facing)
- [ ] Alignment: Uses same patterns as existing codebase
- [ ] Alignment: File/folder structure follows conventions

**Decision Points - STOP and Clarify If:**
- Multiple architectural patterns could work (REST vs GraphQL, SQL vs NoSQL)
- Existing codebase has inconsistent patterns
- Feature requires breaking changes to existing APIs
- Performance requirements not specified but could influence design
- Security model choice affects UX
- Technology choice not specified (which library?)
- Integration approach with third-party services unclear

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

**Quality Checklist:**
- [ ] Granularity: Each task completable in 1-4 hours
- [ ] Granularity: Tasks with >4 files should be split
- [ ] Granularity: No task depends on more than 2 others
- [ ] Task Definition: Title is action-oriented ("Add...", "Create...", "Update...")
- [ ] Task Definition: Description explains what to do
- [ ] Task Definition: Files to modify/create listed explicitly
- [ ] Task Definition: 2-4 acceptance criteria per task
- [ ] Criteria Quality: Start with testable verb ("Verify...", "Confirm...")
- [ ] Criteria Quality: Reference specific behavior or output
- [ ] Criteria Quality: Can be verified in <5 minutes
- [ ] Dependencies: Reference valid task IDs
- [ ] Dependencies: No circular dependencies
- [ ] Dependencies: First task has no dependencies
- [ ] Coverage: All architecture components have tasks
- [ ] Coverage: Test tasks included (not just implementation)

**Decision Points - STOP and Clarify If:**
- Task order could reasonably vary
- Some tasks could be parallelized vs sequential
- Test coverage expectations not defined (unit? integration? e2e?)
- Edge cases could be separate tasks or bundled

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

**Quality Checklist (per task):**
- [ ] Completion: All files listed in task are created/modified
- [ ] Completion: Each acceptance criterion verified
- [ ] Completion: Code compiles/runs without errors
- [ ] Code Quality: No hardcoded values that should be configurable
- [ ] Code Quality: Functions are <50 lines (or justified)
- [ ] Code Quality: No commented-out code blocks
- [ ] Code Quality: Error cases handled (not just happy path)
- [ ] Testing: At least 1 test per public function/endpoint
- [ ] Testing: Tests cover success and error cases
- [ ] Testing: All tests pass
- [ ] Documentation: Public APIs have docstrings/comments
- [ ] Documentation: Complex logic has inline comments

**Decision Points - STOP and Clarify If:**
- Acceptance criteria can be interpreted differently
- Implementation approach differs from architecture
- conventions.md missing or doesn't cover scenario
- Multiple valid error handling strategies exist
- Third-party API has multiple versions/options
- Test data requirements unclear (mock vs real)
- Performance trade-off exists (speed vs memory)

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

**Pass/Fail Criteria:**
- **FAIL (REQUEST_CHANGES) if:** Any Critical issue, >3 Warnings, security vulnerability, or <50% test coverage

**Issue Severity Definitions:**
- **Critical (blocks):** Security vulnerabilities, data loss potential, breaks existing functionality, missing error handling on external calls
- **Warning (should fix):** Code duplication >10 lines, missing input validation, performance concerns (N+1, unbounded loops), convention violations
- **Suggestion (optional):** Naming improvements, code organization, additional tests, documentation enhancements

**Quality Checklist:**
- [ ] Every modified file has been read
- [ ] Security checklist completed
- [ ] Performance implications assessed
- [ ] Convention compliance verified

**Decision Points - STOP and Clarify If:**
- Issue severity is borderline between levels
- Code works but doesn't match architecture
- Convention violation is intentional for good reason
- Security concern exists but fix increases complexity significantly
- Review finds PRD/Architecture gaps
- Third-party dependency has known vulnerabilities

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

**Pass/Fail Criteria:**
- **FAIL if:** Any test fails, Critical bug found, acceptance criterion not verified, or regression in existing functionality

**Bug Severity Definitions:**
- **Critical (blocks):** Application crash, data corruption/loss, security vulnerability, core functionality broken
- **High (blocks):** Feature doesn't work as specified, >50% performance degradation, UI completely broken
- **Medium (can release with plan):** Edge case failures, minor performance issues, cosmetic UI problems
- **Low (can release):** Minor inconveniences, rare edge cases, polish items

**Quality Checklist:**
- [ ] All acceptance criteria tested with results documented
- [ ] Unit tests executed and pass
- [ ] Integration tests executed (if available)
- [ ] Manual verification of core flows
- [ ] Edge cases tested (empty input, invalid input, boundaries)

**Decision Points - STOP and Clarify If:**
- Bug severity is borderline (Medium could be High)
- Test environment differs from production
- Edge case behavior undefined in PRD
- Performance testing needed but no baseline defined
- Flaky tests exist
- Partial functionality works - release with known issues?
- Acceptance criterion passes technically but UX feels wrong

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

**Quality Checklist:**
- [ ] README mentions new feature (if user-visible)
- [ ] API documentation added for new endpoints
- [ ] CHANGELOG entry created
- [ ] API: Request/response examples provided (if applicable)
- [ ] API: Error responses documented (if applicable)
- [ ] User docs: Feature purpose explained (if user-visible)
- [ ] User docs: Usage instructions provided (if user-visible)
- [ ] Changelog: Version/date included
- [ ] Changelog: Changes categorized (Added/Changed/Fixed/Removed)
- [ ] Changelog: Breaking changes clearly marked
- [ ] Changelog: Migration steps provided if needed

**Decision Points - STOP and Clarify If:**
- Documentation style/format inconsistent across project
- Feature is internal-only vs user-facing unclear
- Breaking changes exist but migration path complex
- Version numbering scheme not established
- Multiple documentation locations exist (README, wiki, docs site)
- API documentation format not specified (OpenAPI, JSDoc, etc.)

**Workflow Complete!**

---

## Quality Gates Summary

| Transition | Gate Requirements |
|------------|-------------------|
| → PRD_READY | PRD has problem statement (2-4 sentences), 3-5 goals, 2+ user stories with 2-5 acceptance criteria each, bounded scope |
| → PLAN_APPROVED | Architecture has system context, component design (single responsibility, interfaces), implementation phases (2-6) |
| → TASKLIST_READY | Tasks are 1-4 hours each, have action-oriented titles, 2-4 acceptance criteria, no circular dependencies |
| → IMPLEMENT_STEP_OK | All tasks completed, all acceptance criteria met, tests pass, code compiles |
| → REVIEW_OK | No Critical issues, ≤3 Warnings, security check passed, all files reviewed |
| → RELEASE_READY | All tests pass, no Critical/High bugs, all acceptance criteria verified |
| → DOCS_UPDATED | README updated (if user-visible), API docs complete, CHANGELOG entry created |

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
