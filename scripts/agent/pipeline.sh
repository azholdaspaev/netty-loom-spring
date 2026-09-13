#!/usr/bin/env bash
# Take a GitHub issue from its worktree to a pull request ready for review: implement, then review
# and fix until no review thread is left open or a round changed nothing -- the fix pushed no
# commit and the review after it posted no comment -- three rounds at most, then verify by test
# and hand over. A stage that asked a question moves the issue to agent/needs-input instead.
# Usage: scripts/agent/pipeline.sh <issue number>    (cwd = the issue's worktree)
set -euo pipefail

N="${1:?usage: pipeline.sh <issue number>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PR_COMMENTS="$HERE/../../.claude/scripts/pr-comments.sh"
LOG="$HOME/.netty-loom-agent/logs/NL-$N"
ROUNDS=3

reset_tree() { git reset -q --hard && git clean -fdq; }

# stage <stage> [<pr url> [<round>]] -- runs stage.sh with its stdout in $stage_out rather than
# echoed for a $(...) caller: an exit inside a command substitution ends only the subshell, and the
# pipeline must end here on a question (issue to agent/needs-input, exit 0) or a failure (its code).
stage() {
  local rc=0
  stage_out=$("$HERE/stage.sh" "$N" "$@") || rc=$?
  case "$rc" in
    0) ;;
    3) # Implement's edits stay for its resumed self; any later stage's would stop the review that resumes.
       [ "$1" = implement ] || reset_tree
       gh issue edit "$N" --remove-label agent/running --add-label agent/needs-input >/dev/null; exit 0 ;;
    *) exit "$rc" ;;
  esac
}

branch=$(git branch --show-current)
url=$(gh pr list --head "$branch" --json url --jq '.[0].url')
if [ -z "$url" ]; then
  stage implement
  url=$stage_out
fi

me=$(gh api user --jq .login)
pr_head() { gh pr view "$url" --json headRefOid --jq .headRefOid; }

converged=0
stalled=0
moved=1
for round in $(seq 1 "$ROUNDS"); do
  since=$("$PR_COMMENTS" "$url" | jq -r '[.inline[].created_at] | max // ""')
  stage review "$url" "$round"
  comments=$("$PR_COMMENTS" "$url")
  posted=$(jq --arg me "$me" --arg since "$since" \
    '[.inline[] | select(.author == $me and .created_at > $since)] | length' <<<"$comments")
  open=$(jq '[.threads[] | select(.isResolved | not)] | length' <<<"$comments")
  if [ "$open" = 0 ]; then converged=1; break; fi
  if [ "$moved" = 0 ] && [ "$posted" = 0 ]; then stalled=1; break; fi
  before=$(pr_head)
  stage fix "$url" "$round"
  moved=0
  [ "$(pr_head)" = "$before" ] || moved=1
done

stage test "$url"
dirty=$(git status --porcelain)
[ -z "$dirty" ] || reset_tree

threads="$open thread$([ "$open" = 1 ] || echo s) open."
if [ "$converged" = 1 ]; then
  outcome="converged."
elif [ "$stalled" = 1 ]; then
  outcome="did not converge: fix $((round - 1)) pushed no commit and review $round posted no comment; $threads"
else
  outcome="did not converge after $ROUNDS rounds; $threads"
fi
cost=$(jq -s '[.[].total_cost_usd] | add' "$LOG"/*.json)
minutes=$(jq -s '([.[].duration_ms] | add) / 60000 | round' "$LOG"/*.json)
results=$(jq -s length "$LOG"/*.json)

gh pr ready "$url"
gh issue edit "$N" --add-label agent/pr-ready >/dev/null
gh issue comment "$N" --body-file - >/dev/null <<BODY
Pull request: $url
Review/fix rounds: $round, $outcome
Cost: $(printf '%.2f' "$cost") USD, wall time: $minutes min, from $results stage results in $LOG.
${dirty:+Tree was dirty after the test stage and was reset with \`git reset --hard && git clean -fd\`:
\`\`\`
$dirty
\`\`\`}
BODY
echo "$url"
