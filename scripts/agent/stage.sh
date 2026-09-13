#!/usr/bin/env bash
# Run one pipeline stage for a GitHub issue as an unattended claude -p and report how it ended:
# exit 0 (with the pull request URL after implement), 3 when a question is pending -- this stage's,
# or an earlier run's still newest on the issue -- 124 on timeout, 2 when the infrastructure failed
# (a gh call, or claude's error_during_execution), else 1.
# Usage (cwd = the issue's worktree): scripts/agent/stage.sh <issue number> implement
#                                     scripts/agent/stage.sh <issue number> review|fix|test <pr url> [<round>]
set -euo pipefail

N="${1:?usage: stage.sh <issue number> <stage> [<pr url> [<round>]]}"
STAGE="${2:?usage: stage.sh <issue number> <stage> [<pr url> [<round>]]}"
PR="${3:-}"
ROUND="${4:-}"
NAME="$STAGE${ROUND:+ $ROUND}"
STAGE_TIMEOUT="${STAGE_TIMEOUT:-45m}"
AGENT="$(cd "$(dirname "$0")/../../.claude/agent" && pwd)"

say() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) stage.sh: NL-$N $NAME: $*" >&2; }
fail() { say "$1"; exit "${2:-1}"; }
gh() { command gh "$@" || fail "gh $1 $2 failed" 2; }

branch=$(git branch --show-current)
case "$branch" in
  NL-"$N"-*) ;;
  *) fail "on '$branch', expected NL-$N-<slug>" ;;
esac

MARKER='<!-- agent:question -->'
repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
me=$(gh api user --jq .login)
comments() { gh api --paginate "repos/$repo/issues/$N/comments?per_page=100" | jq -s 'add // []'; }

ALLOWED="Read,Edit,Write,Grep,Glob,Agent,Skill,Bash(./gradlew *),\
Bash(git status *),Bash(git diff *),Bash(git log *),Bash(git show *),Bash(git add *),\
Bash(git commit *),Bash(git push *),Bash(git stash *),Bash(git checkout -- *),\
Bash(gh issue view *),Bash(gh issue comment *),Bash(gh issue create *),\
Bash(gh pr view *),Bash(gh pr diff *),Bash(gh pr create *),Bash(gh pr comment *),\
Bash(gh api repos/*/pulls/*/comments*),Bash(gh api repos/*/issues/*/comments*),\
Bash(gh api repos/*/pulls/comments/*),Bash(gh api repos/*/issues/comments/*),\
Bash(gh api repos/*/pulls/*/reviews *),Bash(gh api graphql *),Bash(.claude/scripts/pr-comments.sh *),\
Bash(.claude/scripts/check-comments.sh *),\
Bash(ls *),Bash(cat *),Bash(head *),Bash(tail *),Bash(grep *),Bash(find *),Bash(wc *),\
Bash(awk *),Bash(sed -n *),Bash(sort *),Bash(uniq *),Bash(diff *),Bash(jq *)"

# The stage's own steps ride in the system prompt: a -p prompt that starts with a slash
# command is expanded, and anything after the command name becomes its ARGUMENTS text.
TAIL=""
case "$STAGE" in
  implement)
    BUDGET=16
    PROMPT="/flow:implement $N"
    TAIL="## Stage: implement

When the work is committed: \`git push -u origin $branch\`. Then write the pull request body
with the Write tool to \`build/pr-body.md\`, following \`.github/PULL_REQUEST_TEMPLATE.md\`
section by section, and open it with
\`gh pr create --draft --title \"NL-$N <the issue's title>\" --body-file build/pr-body.md\`.
No label, and never mark it ready for review."
    ;;
  review) BUDGET=6; PROMPT="/flow:review $PR" ;;
  fix)    BUDGET=4; PROMPT="/flow:fix $PR" ;;
  test)   BUDGET=6; PROMPT="/flow:test $PR" ;;
  *) fail "unknown stage '$STAGE'" ;;
esac
[ "$STAGE" = implement ] || [ -n "$PR" ] || fail "no pull request given"

LOG="$HOME/.netty-loom-agent/logs/NL-$N"
mkdir -p "$LOG"
OUT="$LOG/$STAGE${ROUND:+-$ROUND}"

say start
# A Gradle daemon keeps the Seatbelt profile it was spawned under and any later client reuses it,
# so the stage starts its own daemon inside the sandbox and leaves none behind for the next build.
trap 'status=$?; ./gradlew --stop >&2 || true; say "end (exit $status)"' EXIT
./gradlew --stop >&2

# The newest comment's own timestamp rather than date -u: GitHub's clock on both sides, so a
# skewed local clock cannot hide this stage's question inside a "no commits" failure.
before=$(comments)
pending=$(jq -r --arg me "$me" --arg marker "$MARKER" \
  '.[-1] // empty | select(.user.login == $me and (.body | startswith($marker))) | .html_url' <<<"$before")
[ -z "$pending" ] || fail "question pending: $pending" 3
since=$(jq -r 'map(.created_at) | max // ""' <<<"$before")

SYSTEM=$(cat "$AGENT/unattended.md")
[ -z "$TAIL" ] || SYSTEM="$SYSTEM"$'\n\n'"$TAIL"

rc=0
timeout "$STAGE_TIMEOUT" claude -p "$PROMPT" \
  --permission-mode acceptEdits --permission-prompts none \
  --allowedTools "$ALLOWED" \
  --append-system-prompt "$SYSTEM" \
  --settings "$AGENT/settings.json" \
  --max-budget-usd "$BUDGET" \
  --output-format json --name "NL-$N $NAME" \
  </dev/null >"$OUT.json" 2>"$OUT.log" || rc=$?

question=$(comments | jq -r --arg me "$me" --arg since "$since" --arg marker "$MARKER" \
  '[.[] | select(.user.login == $me and (.body | startswith($marker)) and .created_at > $since)]
   | first // empty | .html_url') \
  || { question=; echo "stage.sh: NL-$N $STAGE: comments unreadable after the stage; the pull request and commits decide" >&2; }
[ -z "$question" ] || fail "asked a question: $question" 3

[ "$rc" = 124 ] && fail "timed out after $STAGE_TIMEOUT" 124
subtype=$(jq -r '.subtype // empty' "$OUT.json" 2>/dev/null || true)
[ -n "$subtype" ] || fail "no result (claude exited $rc), see $OUT.log"
# .subtype is "success" when the API was never reached (#270); .is_error is what the CLI sets then.
is_error=$(jq -r '.is_error' "$OUT.json")
outcome=$(jq -r '[(.terminal_reason | select(. != "completed"))
                  // (if .subtype == "success" and .is_error == true then "is_error" else .subtype end),
                  (.result // "" | split("\n")[0] // empty | select(. != ""))] | join(": ")' "$OUT.json")
code=1; [ "$subtype" != error_during_execution ] || code=2
[ "$subtype" = success ] && [ "$is_error" != true ] && [ "$rc" = 0 ] \
  || fail "claude ended with $outcome (exited $rc), see $OUT.json" "$code"

case "$STAGE" in
  implement)
    [ -n "$(git log --oneline origin/main..HEAD)" ] || fail "no commits on $branch"
    url=$(gh pr list --head "$branch" --json url --jq '.[0].url')
    [ -n "$url" ] || fail "no open pull request for $branch"
    echo "$url"
    ;;
  fix)
    [ -z "$(git status --porcelain)" ] || fail "uncommitted edits left on $branch"
    head=$(gh pr view "$PR" --json headRefOid --jq .headRefOid)
    [ "$(git rev-parse HEAD)" = "$head" ] || fail "HEAD is not pushed: pull request head is $head"
    ;;
esac
