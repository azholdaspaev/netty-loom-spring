---
name: dev
description: Full development workflow -- clarify, research, TDD, simplify, review, QA
user-invocable: true
model: opus
---

You are orchestrating a full development workflow for the netty-loom-spring project. The task is: $ARGUMENTS

Execute each phase sequentially. Do NOT skip phases. Announce each phase clearly with a header.

## Phase 1: Clarify Requirements

Before doing any work, make sure you fully understand the task:

1. Restate the task in your own words
2. Identify which module(s) are affected: core, mvc, starter, example-netty, example-tomcat
3. List any ambiguities or open questions
4. Ask the user clarifying questions if anything is unclear
5. Once confirmed, summarize the acceptance criteria as a checklist

Do NOT proceed until the user confirms the requirements.

## Phase 2: Research

Delegate to the `research-agent` to explore the codebase and find relevant patterns, dependencies, and prior art. Also search the web if the task involves unfamiliar APIs or libraries.

Pass the confirmed requirements to the research agent.

Review the research findings and share a brief summary with the user before proceeding.

## Phase 3: Development (TDD)

Delegate to the `tdd-dev-agent` with:
- The confirmed requirements
- The research findings
- The acceptance criteria

The agent will write failing tests first, then implement until tests pass.

## Phase 4: Simplify

Use the Skill tool to invoke the `/simplify` skill to review the changed code for reuse, quality, and efficiency.

## Phase 5: Review

Delegate to the `review-agent` to perform a code review of all changes made so far.

Address any issues the review agent identifies before proceeding.

## Phase 6: QA

Delegate to the `qa-agent` to run the full test suite and validate the implementation. Pass the affected module names, acceptance criteria, and a summary of what was implemented.

If QA finds failures:
- Share the failures with the user
- Return to Phase 3 (Development) to fix the issues
- Then re-run Phase 4, 5, 6

If QA passes, summarize what was accomplished and present the final changes to the user.

## Project Context

Refer to `CLAUDE.md` for build commands, module architecture, and Gradle gotchas.
Refer to `conventions.md` for naming, class design, Netty patterns, and testing rules.
