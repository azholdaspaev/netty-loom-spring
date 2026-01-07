# AIDD Workflow Configuration

This project uses **AI-Driven Development (AIDD)** methodology with Claude Code.

## Workflow Overview

This project follows a structured development workflow with quality gates:

```
/idea → PRD_READY → /plan → PLAN_APPROVED → /tasks → TASKLIST_READY
→ /implement → IMPLEMENT_STEP_OK → /review → REVIEW_OK
→ /qa → RELEASE_READY → /docs-update → DOCS_UPDATED
```

## Quick Start

1. **Start a new feature:** `/idea {description}`
2. **Or run full automation:** `/feature-development {description}`
3. **Check status anytime:** `/validate`

## Available Commands

| Command | Purpose | Prerequisites |
|---------|---------|---------------|
| `/idea {description}` | Start new feature, create PRD | None |
| `/researcher` | Technical research | PRD_READY |
| `/plan` | Create architecture plan | PRD_READY |
| `/tasks` | Generate task list | PLAN_APPROVED |
| `/implement TASK-XXX` | Implement specific task | TASKLIST_READY |
| `/review` | Code review | IMPLEMENT_STEP_OK |
| `/qa` | Quality assurance testing | REVIEW_OK |
| `/validate` | Check workflow status | Any |
| `/feature-development {idea}` | Full automated workflow | None |
| `/docs-update` | Update documentation | RELEASE_READY |

## Available Agents

| Agent | Purpose |
|-------|---------|
| `analyst` | Requirements gathering, PRD creation |
| `researcher` | Technical research and analysis |
| `planner` | Architecture design |
| `implementer` | Code implementation |
| `reviewer` | Code review |
| `qa` | Testing and validation |
| `tech-writer` | Documentation |
| `validator` | Workflow state validation |

## Workflow States

| State | Description | Next Commands |
|-------|-------------|---------------|
| `INITIAL` | Workflow just started | /idea |
| `PRD_READY` | PRD created | /researcher, /plan |
| `PLAN_APPROVED` | Architecture ready | /tasks |
| `TASKLIST_READY` | Tasks generated | /implement |
| `IMPLEMENT_STEP_OK` | All tasks done | /review |
| `REVIEW_OK` | Review passed | /qa |
| `RELEASE_READY` | QA passed | /docs-update |
| `DOCS_UPDATED` | Complete | - |

## Quality Gates

**Strict enforcement is enabled.** Each command checks prerequisites before execution:
- Missing prerequisites block command execution
- Clear error messages indicate what's missing
- Use `/validate` to check current status anytime

## Artifact Storage

All workflow artifacts are stored in:
```
.artifacts/{YYYY-MM-DD}-{feature-name}/
├── STATE.md              # Workflow state tracker
├── PRD.md                # Product Requirements Document
├── RESEARCH.md           # Technical research report (optional)
├── ARCHITECTURE.md       # Architecture plan
├── TASKS.md              # Implementation tasks
├── IMPLEMENTATION_LOG.md # Implementation notes
├── REVIEW.md             # Code review report
├── QA_REPORT.md          # QA test report
└── DOCS_SUMMARY.md       # Documentation changes
```

## Project Conventions

See `conventions.md` for project-specific coding standards and conventions. This file should be customized for each project's technology stack.

## Workflow Documentation

See `workflow.md` for detailed documentation of each workflow phase.

## Getting Started

### Option 1: Step-by-Step Workflow
```
/idea Add user authentication with OAuth2
/plan
/tasks
/implement TASK-001
/implement TASK-002
...
/review
/qa
/docs-update
```

### Option 2: Automated Full Workflow
```
/feature-development Add user authentication with OAuth2
```

### Check Status
```
/validate
```

## Best Practices

1. **Start Clean:** Always start new features with `/idea`
2. **Don't Skip Phases:** Each phase builds on previous work
3. **Fix Forward:** When issues found, fix and re-run the phase
4. **Document Everything:** Artifacts serve as project history
5. **Validate Often:** Use `/validate` to check progress
6. **One Feature at a Time:** Complete one feature before starting another
