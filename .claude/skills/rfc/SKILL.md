---
name: rfc
description: Create a comprehensive RFC through a structured workflow — requirements gathering, web research, writing, parallel multi-agent review, and revision. Invoke with /rfc "topic description".
allowed-tools: Read Write Glob Grep Bash Agent Skill AskUserQuestion WebSearch TaskCreate TaskUpdate TaskList
---

# RFC Workflow Orchestrator

You orchestrate the full RFC creation workflow. Use **TaskCreate** and **TaskUpdate** to define and track each step so the user can see progress.

> **Context model:** This skill runs in the main conversation context (no `context: fork`) because it needs to maintain state across sub-skill invocations. Sub-skills use `context: fork` for isolation — they can't see each other's internal state, only the files they write to disk.

## Usage

```
/rfc "brief description of the topic"
```

The topic description is available as: `$ARGUMENTS`

## Step 0: Setup

1. If `$ARGUMENTS` is empty or missing, ask the user for a topic description before proceeding. Do not create any files until you have a topic.
2. If `$ARGUMENTS` looks like a path to an existing RFC directory (contains `RFC-`), enter **resume mode** — skip to Step 0.5 to detect existing state.
3. Scan `docs/rfcs/` for existing RFC directories to determine the next RFC number (start at 0001).
4. Create the RFC directory: `docs/rfcs/RFC-NNNN/`
5. Create the `reviews/` subdirectory inside it.
6. Write the user's original topic description (`$ARGUMENTS`) to `docs/rfcs/RFC-NNNN/topic.md` so downstream skills have the original context.
7. **Create tasks** for the workflow steps using TaskCreate:
   - "Gather requirements" (description: interactive requirements session)
   - "Research prior art and constraints" (description: parallel web + codebase research)
   - "Write RFC draft" (description: produce RFC document from template)
   - "Review RFC" (description: 4 parallel review agents)
   - "Synthesize reviews and decide" (description: revision brief + user decision)
8. Announce to the user: "Starting RFC-NNNN: [topic]."

Store the RFC directory path for passing to sub-skills.

## Step 0.5: Detect Existing State

Before starting the pipeline, check which artifacts already exist in the RFC directory:
- `requirements.md` exists → skip Step 1 (tell the user: "Found existing requirements.md — skipping requirements gathering. Say 'redo requirements' if you want to start fresh.")
- `research.md` exists → skip Step 2
- `RFC-NNNN.md` exists → skip Step 3 (unless `revision-brief.md` also exists, in which case proceed to revision mode in Step 3)
- `reviews/` has review files → skip Step 4

This enables resuming a partially completed RFC. The user can also explicitly request to redo any step.

## Step 1: Requirements

Mark the requirements task as `in_progress` via TaskUpdate.

Invoke the `/rfc-requirements` skill with the RFC directory path:

```
/rfc-requirements docs/rfcs/RFC-NNNN/
```

This step is interactive — the skill will ask the user clarifying questions with options. Wait for it to complete and confirm that `requirements.md` has been written.

Mark the requirements task as `completed`.

## Step 2: Research

Mark the research task as `in_progress`.

Invoke the `/rfc-research` skill with the RFC directory path:

```
/rfc-research docs/rfcs/RFC-NNNN/
```

Wait for it to complete and confirm that `research.md` has been written.

Mark the research task as `completed`.

## Step 3: Write

Mark the write task as `in_progress`.

Invoke the `/rfc-write` skill with the RFC directory path:

```
/rfc-write docs/rfcs/RFC-NNNN/
```

Wait for it to complete and confirm that `RFC-NNNN.md` has been written.

Mark the write task as `completed`.

## Step 4: Review

Mark the review task as `in_progress`.

Invoke the `/rfc-review` skill with the RFC directory path:

```
/rfc-review docs/rfcs/RFC-NNNN/
```

Wait for all 4 review files to be written to `reviews/`.

Mark the review task as `completed`.

## Step 5: Revise

Mark the revise task as `in_progress`.

Invoke the `/rfc-revise` skill with the RFC directory path:

```
/rfc-revise docs/rfcs/RFC-NNNN/
```

This synthesizes review feedback and asks the user to choose: **accept**, **revise all**, **revise selectively**, **add notes**, or **abandon**.

### If user chooses to revise

Track the revision count. **Maximum 2 revision rounds.**

- If revision count < 2: Go back to **Step 3 (Write)** — the write skill will detect `revision-brief.md` and apply targeted edits instead of rewriting from scratch. **After the write step completes**, archive stale review artifacts before running the next review. Use Bash to create a round subdirectory and move the current review files into it: `mkdir -p <RFC_DIR>/reviews/round-N && mv <RFC_DIR>/reviews/*.md <RFC_DIR>/revision-brief.md <RFC_DIR>/reviews/round-N/` (where N is the completed round number). This preserves review history. Then repeat Step 4 (Review) and Step 5 (Revise). Create new tasks for revision round steps as needed.
- If revision count = 2: Inform the user that the maximum revision rounds have been reached. The revise skill will offer appropriate options (accept, accept with caveats, or restart).

### If user chooses "accept"

Mark the revise task as `completed`. Announce completion:
```
RFC-NNNN is finalized at: docs/rfcs/RFC-NNNN/RFC-NNNN.md

Artifacts produced:
- RFC document: RFC-NNNN.md
- Original topic: topic.md
- Requirements: requirements.md
- Research: research.md
- Reviews: reviews/ (2-4 files depending on RFC scope)
- Revision brief: revision-brief.md (if revised)
```

### If user chooses "abandon"

Mark the revise task as `completed`. Acknowledge and stop. Do not delete the files — the user may want to reference them later.

## Error Handling

- If a sub-skill fails or produces incomplete output, inform the user and ask whether to retry that step or skip it
- If a required input file is missing (e.g., research.md before write), inform the user and go back to the missing step
- Never silently skip a step

## State Tracking

All state is file-based in `docs/rfcs/RFC-NNNN/`:

```
docs/rfcs/RFC-NNNN/
├── topic.md                  # Original topic description (Step 0)
├── RFC-NNNN.md               # The RFC (Step 3)
├── requirements.md           # Requirements (Step 1)
├── research.md               # Research (Step 2)
├── reviews/                  # Reviews (Step 4)
│   ├── architecture.md
│   ├── security.md
│   ├── user-experience.md
│   ├── testability-operability.md
│   └── round-1/              # Archived reviews from revision round 1 (if any)
└── revision-brief.md         # Revision synthesis (Step 5)
```
