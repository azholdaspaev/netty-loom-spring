#!/usr/bin/env bash
# Take a GitHub issue from its worktree to a pull request ready for review: implement (or move
# the issue to agent/needs-input when that stage asked a question), then review and fix until no
# review thread is left open (three rounds at most), then verify by test and hand over.
# Usage: scripts/agent/pipeline.sh <issue number>    (cwd = the issue's worktree)
set -euo pipefail

N="${1:?usage: pipeline.sh <issue number>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PR_COMMENTS="$HERE/../../.claude/scripts/pr-comments.sh"
LOG="$HOME/.netty-loom-agent/logs/NL-$N"
ROUNDS=3

branch=$(git branch --show-current)
url=$(gh pr list --head "$branch" --json url --jq '.[0].url')
if [ -z "$url" ]; then
  rc=0
  url=$("$HERE/stage.sh" "$N" implement) || rc=$?
  case "$rc" in
    0) ;;
    3) gh issue edit "$N" --remove-label agent/running --add-label agent/needs-input; exit 0 ;;
    *) exit "$rc" ;;
  esac
fi

converged=0
for round in $(seq 1 "$ROUNDS"); do
  "$HERE/stage.sh" "$N" review "$url" "$round"
  open=$("$PR_COMMENTS" "$url" | jq '[.threads[] | select(.isResolved | not)] | length')
  if [ "$open" = 0 ]; then converged=1; break; fi
  "$HERE/stage.sh" "$N" fix "$url" "$round"
done

"$HERE/stage.sh" "$N" test "$url"
dirty=$(git status --porcelain)
[ -z "$dirty" ] || git checkout -- .

if [ "$converged" = 1 ]; then
  outcome="converged."
else
  outcome="did not converge after $ROUNDS rounds; $open thread$([ "$open" = 1 ] || echo s) open."
fi
cost=$(jq -s '[.[].total_cost_usd] | add' "$LOG"/*.json)
minutes=$(jq -s '([.[].duration_ms] | add) / 60000 | round' "$LOG"/*.json)
results=$(jq -s length "$LOG"/*.json)

gh pr ready "$url"
gh issue edit "$N" --add-label agent/pr-ready
gh issue comment "$N" --body-file - <<BODY
Pull request: $url
Review/fix rounds: $round, $outcome
Cost: $(printf '%.2f' "$cost") USD, wall time: $minutes min, from $results stage results in $LOG.
${dirty:+Tree was dirty after the test stage and was reset with \`git checkout -- .\`:
\`\`\`
$dirty
\`\`\`}
BODY
echo "$url"
