#!/usr/bin/env bash
# The part of CLAUDE.md rule 5 a script can check: no javadoc on a @Test or @ParameterizedTest
# method, a class javadoc within 20 lines, a private member's within 2, and no run of two or more
# full-line // comments -- a paragraph is a /** */ or /* */ block, so the budgets can see it.
# Usage: .claude/scripts/check-comments.sh <file.java>...
#
# Length is the number of lines between /** and */, counting the /** and */ lines themselves only
# when they carry text; `/** text */` is 1.
# The 8-line class budget is not checked: whether trigger 1 raised it to 20 is a reading of the
# text, not a count. A private nested class is a private member. Each failure names the file and
# the line of the /** or of the run's first //; the exit status is 1 whenever anything was printed.
set -eu
export LC_ALL=C

: "${1:?usage: check-comments.sh <file.java>...}"

awk '
function fail(line, msg) { print "check-comments: " FILENAME ":" line ": " msg > "/dev/stderr"; failed = 1 }
FNR == 1 { state = 0; slashes = 0 }
/^[[:space:]]*\/\// { if (++slashes == 2) fail(FNR - 1, "two consecutive full-line // comments; a paragraph is a /** */ or /* */ block") }
!/^[[:space:]]*\/\// { slashes = 0 }
/^[[:space:]]*\/\*\*/ {
    start = FNR; lines = 0; test = 0; depth = 0
    if ($0 ~ /\*\//) { lines = 1; state = 2 }
    else { rest = $0; sub(/^[[:space:]]*\/\*\*[[:space:]]*/, "", rest); lines = (rest != ""); state = 1 }
    next
}
state == 1 {
    if ($0 !~ /\*\//) { lines++; next }
    rest = $0; sub(/[[:space:]]*\*\/.*$/, "", rest); sub(/^[[:space:]]*\*?[[:space:]]*/, "", rest)
    lines += (rest != ""); state = 2
    next
}
state == 2 {
    if ($0 ~ /^[[:space:]]*(\/\/.*|\/\*.*\*\/)?$/) next
    if (depth > 0 || $0 ~ /^[[:space:]]*@/) {
        if ($0 ~ /@(Test|ParameterizedTest)([^A-Za-z0-9_]|$)/) test = 1
        t = $0; gsub(/"[^"]*"/, "", t); sub(/\/\/.*$/, "", t)
        depth += gsub(/\(/, "(", t) - gsub(/\)/, ")", t)
        next
    }
    if (test) fail(start, "javadoc on a @Test method; the budget is none")
    else if ($0 ~ /(^|[[:space:]])private[[:space:]]/) { if (lines > 2) fail(start, "private member javadoc is " lines " lines; the limit is 2") }
    else if ($0 ~ /(^|[[:space:]])(class|interface|enum|record)[[:space:]]/) { if (lines > 20) fail(start, "class javadoc is " lines " lines; the limit is 20") }
    state = 0
}
END { if (failed) print "  see CLAUDE.md rule 5" > "/dev/stderr"; exit failed }
' "$@"
