#!/usr/bin/env bash
# Run one pipeline stage for a GitHub issue as an unattended claude -p and report how it ended.
# Usage: scripts/agent/stage.sh <issue number> implement    (cwd = the issue's worktree)
set -euo pipefail

N="${1:?usage: stage.sh <issue number> <stage>}"
STAGE="${2:?usage: stage.sh <issue number> <stage>}"
STAGE_TIMEOUT="${STAGE_TIMEOUT:-45m}"
AGENT="$(cd "$(dirname "$0")/../../.claude/agent" && pwd)"

fail() { echo "stage.sh: NL-$N $STAGE: $1" >&2; exit "${2:-1}"; }

branch=$(git branch --show-current)
case "$branch" in
  NL-"$N"-*) ;;
  *) fail "on '$branch', expected NL-$N-<slug>" ;;
esac

ALLOWED="Read,Edit,Write,Grep,Glob,Agent,Skill,Bash(./gradlew *),\
Bash(git status *),Bash(git diff *),Bash(git log *),Bash(git show *),Bash(git add *),\
Bash(git commit *),Bash(git push *),Bash(git stash *),Bash(git checkout -- *),\
Bash(gh issue view *),Bash(gh issue comment *),Bash(gh issue create *),\
Bash(gh pr view *),Bash(gh pr diff *),Bash(gh pr create *),Bash(gh pr comment *),\
Bash(gh api repos/*/pulls/*/comments/*/replies *),Bash(gh api repos/*/pulls/*/reviews *),\
Bash(gh api graphql *),Bash(.claude/scripts/pr-comments.sh *)"

# The stage's own steps ride in the system prompt: a -p prompt that starts with a slash
# command is expanded, and anything after the command name becomes its ARGUMENTS text.
case "$STAGE" in
  implement)
    BUDGET=8
    PROMPT="/flow:implement $N"
    TAIL="## Stage: implement

When the work is committed: \`git push -u origin $branch\`. Then write the pull request body
with the Write tool to \`build/pr-body.md\`, following \`.github/PULL_REQUEST_TEMPLATE.md\`
section by section, and open it with
\`gh pr create --draft --title \"NL-$N <the issue's title>\" --body-file build/pr-body.md\`.
No label, never mark it ready for review, and never pass the body inline or through a heredoc."
    ;;
  *) fail "unknown stage '$STAGE'" ;;
esac

LOG="$HOME/.netty-loom-agent/logs/NL-$N"
mkdir -p "$LOG"

# A Gradle daemon keeps the Seatbelt profile it was spawned under and any later client reuses it,
# so the stage starts its own daemon inside the sandbox and leaves none behind for the next build.
trap './gradlew --stop >&2 || true' EXIT
./gradlew --stop >&2

rc=0
timeout "$STAGE_TIMEOUT" claude -p "$PROMPT" \
  --permission-mode acceptEdits --permission-prompts none \
  --allowedTools "$ALLOWED" \
  --append-system-prompt "$(cat "$AGENT/unattended.md")"$'\n\n'"$TAIL" \
  --settings "$AGENT/settings.json" \
  --max-budget-usd "$BUDGET" \
  --output-format json --name "NL-$N $STAGE" \
  </dev/null >"$LOG/$STAGE.json" 2>"$LOG/$STAGE.log" || rc=$?

[ "$rc" = 124 ] && fail "timed out after $STAGE_TIMEOUT" 124
subtype=$(jq -r '.subtype // empty' "$LOG/$STAGE.json" 2>/dev/null || true)
[ -n "$subtype" ] || fail "no result (claude exited $rc), see $LOG/$STAGE.log"
[ "$subtype" = success ] || fail "claude ended with $subtype, see $LOG/$STAGE.json"

[ -n "$(git log --oneline origin/main..HEAD)" ] || fail "no commits on $branch"
url=$(gh pr list --head "$branch" --json url --jq '.[0].url')
[ -n "$url" ] || fail "no open pull request for $branch"
echo "$url"
