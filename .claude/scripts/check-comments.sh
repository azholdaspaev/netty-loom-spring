#!/usr/bin/env bash
# The part of CLAUDE.md rule 5's comment budgets a script can check: no javadoc on a @Test or
# @ParameterizedTest method, a class javadoc within 20 lines, a private member's within 2.
# Usage: .claude/scripts/check-comments.sh <file.java>...
#
# Length is the number of lines strictly between the /** line and the */ line; `/** text */` is 1.
# The 8-line class budget is not checked: whether trigger 1 raised it to 20 is a reading of the
# text, not a count. A private nested class is a private member. Each failure names the file and
# the line of the /**; the exit status is 1 whenever anything was printed.
set -eu
export LC_ALL=C

: "${1:?usage: check-comments.sh <file.java>...}"

awk '
function fail(msg) { print "check-comments: " FILENAME ":" start ": " msg > "/dev/stderr"; failed = 1 }
FNR == 1 { state = 0 }
/^[[:space:]]*\/\*\*/ {
    start = FNR; lines = 0; test = 0; depth = 0
    if ($0 ~ /\*\//) { lines = 1; state = 2 } else { state = 1 }
    next
}
state == 1 {
    if ($0 ~ /\*\//) state = 2; else lines++
    next
}
state == 2 {
    if ($0 ~ /^[[:space:]]*$/) next
    if (depth > 0 || $0 ~ /^[[:space:]]*@/) {
        if ($0 ~ /@(Test|ParameterizedTest)([^A-Za-z0-9_]|$)/) test = 1
        depth += gsub(/\(/, "(") - gsub(/\)/, ")")
        next
    }
    if (test) fail("javadoc on a @Test method; the budget is none")
    else if ($0 ~ /(^|[[:space:]])private[[:space:]]/) { if (lines > 2) fail("private member javadoc is " lines " lines; the limit is 2") }
    else if ($0 ~ /(^|[[:space:]])(class|interface|enum|record)[[:space:]]/) { if (lines > 20) fail("class javadoc is " lines " lines; the limit is 20") }
    state = 0
}
END { exit failed }
' "$@"
