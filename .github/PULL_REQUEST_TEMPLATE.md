Closes #NN.

## Problem

<!-- What was wrong, and the consequence a user hits. -->

## What changed

<!-- The approach, and the alternative you rejected. Commits are capped at 500
     characters, so this is where the reasoning is recorded.

     Add "## Two things worth a reviewer's attention" as a numbered list when a
     judgement call needs pointing at. -->

## Verification

<!-- ./gradlew build result, the test count, and each mutation you applied with
     what it broke. -->

## Out of scope

<!-- Named gaps and the issue that owns each. Delete the section if the change
     leaves nothing open. -->

## Checklist

- [ ] `./gradlew build` passes locally
- [ ] Every production change has a test that fails without it, and you have confirmed that by
      mutation
- [ ] Every comment you added fires a trigger from `CLAUDE.md` rule 5, and you can name which one
- [ ] Every changed line traces to the issue
- [ ] Documentation that the change falsifies is rewritten, not appended to — including
      `README.md`, `docs/compatibility-matrix.md` and `docs/configuration.md` when behaviour or a
      property changes
- [ ] A user-visible change has a `CHANGELOG.md` entry
