---
name: Bug report
about: A defect in behaviour that already ships
title: ''
labels: bug
assignees: ''
---

<!-- Add exactly one priority/P0|P1|P2 label, at most one area/* label, and a
     milestone. Only the kind label is preset. -->

## Summary

<!-- Open with provenance when it came from a review or another PR:
     "Found while fixing #91; deliberately left out of that PR because ..."
     Then the defect, quoting the offending lines and pinning the sha you
     verified against. -->

## Impact

<!-- What breaks for a user, and what makes it reachable. -->

## Fix notes

<!-- The decision worth making, and the options. Tomcat's behaviour where
     parity is the question. -->

## Pointers

- `module/src/main/java/.../File.java:LINE-LINE` — what is there

## TDD entry point

<!-- The exact test method that must go red, and what removing the fix does
     to it. -->
`SomeTest#theTestThatMustGoRed`
