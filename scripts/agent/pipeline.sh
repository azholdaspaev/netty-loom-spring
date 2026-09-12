#!/usr/bin/env bash
# Take a GitHub issue from its worktree to a pull request ready for review: implement (or move
# the issue to agent/needs-input when that stage asked a question), then review and fix until no
# review thread is left open or a round changed nothing -- the fix pushed no commit and the review
# after it posted no comment -- three rounds at most, then verify by test and hand over.
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
    3) gh issue edit "$N" --remove-label agent/running --add-label agent/needs-input >/dev/null; exit 0 ;;
    *) exit "$rc" ;;
  esac
fi

pr=${url##*/}
repo=${url#https://github.com/}
repo=${repo%/pull/*}
me=$(gh api user --jq .login)
inline() { gh api --paginate "repos/$repo/pulls/$pr/comments?per_page=100" | jq -s 'add // []'; }
pr_head() { gh pr view "$url" --json headRefOid --jq .headRefOid; }

converged=0
stalled=0
moved=1
for round in $(seq 1 "$ROUNDS"); do
  since=$(inline | jq -r 'map(.created_at) | max // ""')
  "$HERE/stage.sh" "$N" review "$url" "$round"
  posted=$(inline | jq --arg me "$me" --arg since "$since" \
    '[.[] | select(.user.login == $me and .created_at > $since)] | length')
  open=$("$PR_COMMENTS" "$url" | jq '[.threads[] | select(.isResolved | not)] | length')
  if [ "$open" = 0 ]; then converged=1; break; fi
  if [ "$moved" = 0 ] && [ "$posted" = 0 ]; then stalled=1; break; fi
  before=$(pr_head)
  "$HERE/stage.sh" "$N" fix "$url" "$round"
  moved=0
  [ "$(pr_head)" = "$before" ] || moved=1
done

"$HERE/stage.sh" "$N" test "$url"
dirty=$(git status --porcelain)
[ -z "$dirty" ] || git checkout -- .

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
${dirty:+Tree was dirty after the test stage and was reset with \`git checkout -- .\`:
\`\`\`
$dirty
\`\`\`}
BODY
echo "$url"
