#!/usr/bin/env bash
# Take a GitHub issue from its worktree to a pull request ready for review: implement, then review
# and fix until no review thread is left open or a round changed nothing -- the fix pushed no
# commit and the review after it posted no comment -- three rounds at most, then verify by test
# and hand over. A stage that asked a question moves the issue to agent/needs-input instead.
# A reused worktree is settled first: uncommitted edits go to a named stash, and commits without
# a pull request get one opened here, with the template as its body, rather than a second
# implement stage on a branch that already carries the work -- unless the owner's answer to the
# issue's last question is newer than every commit, so no implement stage has read it yet.
# Usage: scripts/agent/pipeline.sh <issue number>    (cwd = the issue's worktree)
set -euo pipefail

N="${1:?usage: pipeline.sh <issue number>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PR_COMMENTS="$HERE/../../.claude/scripts/pr-comments.sh"
PR_TEMPLATE="$HERE/../../.github/PULL_REQUEST_TEMPLATE.md"
LOG="$HOME/.netty-loom-agent/logs/NL-$N"
ROUNDS=3
MARKER='<!-- agent:question -->'

reset_tree() { git reset -q --hard && git clean -fdq; }
say() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) pipeline.sh: NL-$N: $*" >&2; }
fail() { say "$1 failed"; exit 2; }
gh() { command gh "$@" || fail "gh $1 $2"; }
pr_comments() { "$PR_COMMENTS" "$url" || fail pr-comments.sh; }
# requeue.sh's marker and rindex, so the two agree on which comment is the question. The answer is
# the owner's first comment after it, not the newest as in requeue.sh: the runner's failure comment
# on a killed retry follows the answer, and must not hide it.
# Prints when the answer was posted, in epoch seconds, or nothing.
answered_at() {
  gh api --paginate "repos/$repo/issues/$N/comments?per_page=100" | jq -s 'add // []' \
    | jq -r --arg me "$me" --arg owner "${repo%%/*}" --arg marker "$MARKER" '
        (map(.user.login == $me and (.body | startswith($marker))) | rindex(true)) as $question
        | select($question != null)
        | .[$question + 1:] | map(select(.user.login == $owner)) | first // empty
        | .created_at | fromdateiso8601'
}

# stage <stage> [<pr url> [<round>]] -- runs stage.sh with its stdout in $stage_out rather than
# echoed for a $(...) caller: an exit inside a command substitution ends only the subshell, and the
# pipeline must end here on a question (issue to agent/needs-input, exit 0) or a failure (its code).
stage() {
  local rc=0
  stage_out=$("$HERE/stage.sh" "$N" "$@") || rc=$?
  case "$rc" in
    0) ;;
    3) # Implement's edits stay for the retry's stash; a later stage's are discarded rather than stashed: scratch from a stage that only asked.
       [ "$1" = implement ] || reset_tree
       # command gh, not the wrapper: a retry reruns the stage, which stops on its own pending question and exits 0.
       command gh issue edit "$N" --remove-label agent/running --add-label agent/needs-input >/dev/null; exit 0 ;;
    124|2) # Killed or dropped mid-edit and retried by the runner, so the same reset; a work failure's tree stays for the maintainer.
       [ "$1" = implement ] || reset_tree
       exit "$rc" ;;
    *) exit "$rc" ;;
  esac
}

branch=$(git branch --show-current)
repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
me=$(gh api user --jq .login)
stash=""
if [ -n "$(git status --porcelain)" ]; then
  stash="NL-$N retry $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  git stash push -q -u -m "$stash"
  say "uncommitted edits found on pick-up, stashed as '$stash'"
fi
url=$(gh pr list --head "$branch" --json url --jq '.[0].url')
answered=$(answered_at)
newest=$(git log -1 --format=%ct origin/main..HEAD)
# An answer older than the newest commit was read by the implement stage that made the commit.
if [ -z "$url" ] && [ -n "$newest" ] && [ "${answered:-0}" -lt "$newest" ]; then
  git push -q -u origin "$branch" || fail "git push"
  title=$(gh issue view "$N" --json title --jq .title)
  body="$(sed "s/#NN/#$N/" "$PR_TEMPLATE")

---

Opened by \`pipeline.sh\` on a retry that found these commits on the branch and no pull request:
no implement stage wrote this body, so the sections above are the template's."
  url=$(gh pr create --draft --title "NL-$N $title" --body-file - <<<"$body")
  say "commits found on pick-up without a pull request, opened $url"
elif [ -z "$url" ]; then
  stage implement
  url=$stage_out
fi

pr_head() { gh pr view "$url" --json headRefOid --jq .headRefOid; }

converged=0
stalled=0
moved=1
for round in $(seq 1 "$ROUNDS"); do
  since=$(pr_comments | jq -r '[.inline[].created_at] | max // ""')
  stage review "$url" "$round"
  comments=$(pr_comments)
  posted=$(jq --arg me "$me" --arg since "$since" \
    '[.inline[] | select(.author == $me and .created_at > $since)] | length' <<<"$comments")
  open=$(jq '[.threads[] | select(.isResolved | not)] | length' <<<"$comments")
  if [ "$open" = 0 ]; then converged=1; break; fi
  if [ "$moved" = 0 ] && [ "$posted" = 0 ]; then stalled=1; break; fi
  before=$(pr_head)
  stage fix "$url" "$round"
  moved=0
  after=$(pr_head)
  [ "$after" = "$before" ] || moved=1
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
${stash:+Uncommitted edits found on pick-up are stashed in the worktree as \`$stash\`.}
${dirty:+Tree was dirty after the test stage and was reset with \`reset_tree\` (\`pipeline.sh\`):
\`\`\`
$dirty
\`\`\`}
BODY
echo "$url"
