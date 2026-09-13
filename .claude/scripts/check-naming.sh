#!/usr/bin/env bash
# The part of CLAUDE.md rule 7 a script can check: no article in a method name, a @Test,
# @ParameterizedTest or @RepeatedTest method named should..., no name over 60 characters, no
# non-@Override method starting with a verb from the table's Not column, and no two names in one
# file equal ignoring case.
# Usage: .claude/scripts/check-naming.sh <file.java>...
#
# A declaration is a line of the shape `<modifiers> <type> <name>(`; a name on the line after its
# type is not seen, and its annotation is read as the next declaration's. Each failure names the
# file and the declaration's line; the exit status is 1 whenever anything was printed.
set -eu
export LC_ALL=C

: "${1:?usage: check-naming.sh <file.java>...}"

awk '
function fail(line, msg) { print "check-naming: " FILENAME ":" line ": " msg > "/dev/stderr"; failed = 1 }
FNR == 1 { test = 0; override = 0; split("", seen) }
/^[[:space:]]*(\/\/|\/\*|\*)/ { next }
/@(Test|ParameterizedTest|RepeatedTest)([^A-Za-z0-9_]|$)/ { test = 1 }
/@Override([^A-Za-z0-9_]|$)/ { override = 1 }
{
    line = $0; gsub(/"[^"]*"/, "", line); sub(/\/\/.*$/, "", line)
    if (!match(line, /^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*[[:space:]]+)*[A-Za-z_][][A-Za-z0-9_.<>,? ]*[[:space:]]+[a-z][A-Za-z0-9_]*[[:space:]]*\(/)) next
    decl = substr(line, RSTART, RLENGTH); sub(/[[:space:]]*\($/, "", decl)
    n = split(decl, word, /[[:space:]]+/); name = word[n]
    if (name ~ /^(if|for|while|switch|catch|return|new|throw|synchronized|try|else|do|yield|case|assert)$/) next
    if (n >= 2 && word[n - 1] ~ /^(return|new|throw|else|case|yield|assert|do|instanceof|throws)$|[<,]$/) next
    if (name ~ /[a-z](A|An|The)[A-Z]|^(a|an|the)[A-Z]/) fail(FNR, "article in method name " name)
    if (test && name !~ /^should/) fail(FNR, "test method " name " does not start with should")
    if (length(name) > 60) fail(FNR, "method name " name " is " length(name) " characters; the budget is 60")
    if (!override && name ~ /^(verify|check|notify|create|build|as)([A-Z]|$)/) fail(FNR, "vocabulary: " name " starts with a verb from the Not column of the rule 7 table")
    lower = tolower(name)
    if (lower in seen && seen[lower] != name) fail(FNR, "case collision: " name " and " seen[lower] " in one file")
    else seen[lower] = name
    test = 0; override = 0
}
END { if (failed) print "  see CLAUDE.md rule 7" > "/dev/stderr"; exit failed }
' "$@"
