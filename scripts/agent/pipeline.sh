#!/usr/bin/env bash
# Take a GitHub issue from its worktree to a pull request ready for review: implement, then review
# and fix until no review thread is left open or a round changed nothing -- the fix pushed no
# commit and the review after it posted no comment -- three rounds at most, then verify by test
# and hand over. A stage that asked a question moves the issue to agent/needs-input instead.
# The script, not the implement stage, pushes the branch and opens the draft pull request, with the
# body the stage wrote to build/pr-body.md, or the template when there is none.
# A reused worktree is settled first: uncommitted edits go to a named stash, and commits without
# a pull request get one opened here rather than a second implement stage on a branch that already
# carries the work -- unless the issue's last question is still pending, or the owner's answer to it
# is newer than every commit, so no implement stage has read it yet.
# Usage: scripts/agent/pipeline.sh <issue number>    (cwd = the issue's worktree)
set -euo pipefail

N="${1:?usage: pipeline.sh <issue number>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PR_COMMENTS="$HERE/../../.claude/scripts/pr-comments.sh"
PR_TEMPLATE="$HERE/../../.github/PULL_REQUEST_TEMPLATE.md"
RUN_ID=$(date -u +%Y%m%dT%H%M%SZ)
export RUN_ID
LOG="$HOME/.netty-loom-agent/logs/NL-$N/$RUN_ID"
ROUNDS=3
MARKER='<!-- agent:question -->'

reset_tree() {
  local dropped
  dropped=$(git log --format='%h %s' "$1..HEAD" | paste -sd ';' -)
  [ -z "$dropped" ] || say "unpushed commits discarded by the reset to $1: $dropped"
  git reset -q --hard "$1" && git clean -fdq
}
say() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) pipeline.sh: NL-$N: $*" >&2; }
fail() { say "$1 failed"; exit 2; }
gh() { command gh "$@" || fail "gh $1 $2"; }
pr_comments() { "$PR_COMMENTS" "$url" || fail pr-comments.sh; }
# requeue.sh's marker and rindex, so the two agree on which comment is the question. The answer is
# the owner's first comment after it, not the newest as in requeue.sh: the runner's failure comment
# on a killed retry follows the answer, and must not hide it.
# Prints when the answer was posted, in epoch seconds; "pending" for a question without one; nothing
# when no question was asked.
answered_at() {
  gh api --paginate "repos/$repo/issues/$N/comments?per_page=100" | jq -s 'add // []' \
    | jq -r --arg me "$me" --arg owner "${repo%%/*}" --arg marker "$MARKER" '
        (map(.user.login == $me and (.body | startswith($marker))) | rindex(true)) as $question
        | select($question != null)
        | .[$question + 1:] | map(select(.user.login == $owner)) | first
        | if . == null then "pending" else .created_at | fromdateiso8601 end'
}

# stage <stage> [<pr url> [<round>]]
stage() {
  local rc=0
  "$HERE/stage.sh" "$N" "$@" || rc=$?
  case "$rc" in
    0) ;;
    3) # Implement's edits stay for the retry's stash; a later stage's, and a commit it left unpushed, are discarded: scratch from a stage that only asked, and the resume reviews the pull request head.
       [ "$1" = implement ] || reset_tree "origin/$branch"
       # command gh, not the wrapper: a retry reruns the stage, which stops on its own pending question and exits 0.
       command gh issue edit "$N" --remove-label agent/running --add-label agent/needs-input >/dev/null; exit 0 ;;
    124|2) # Killed or dropped mid-edit and retried by the runner, so the same reset; a work failure's tree stays for the maintainer.
       [ "$1" = implement ] || reset_tree "origin/$branch"
       exit "$rc" ;;
    *) exit "$rc" ;;
  esac
}

branch=$(git branch --show-current)
repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
me=$(gh api user --jq .login)
if [ -n "$(git status --porcelain)" ]; then
  stash="NL-$N retry $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  git stash push -q -u -m "$stash"
  say "uncommitted edits found on pick-up, stashed as '$stash'"
fi
url=$(gh pr list --head "$branch" --json url --jq '.[0].url')
answered=$(answered_at)
newest=$(git log -1 --format=%ct origin/main..HEAD)
# An answer older than the newest commit was read by the implement stage that made the commit; a
# pending question has none to read, so implement runs and stops on it.
if [ -z "$url" ] && { [ -z "$newest" ] || [ "$answered" = pending ] || [ "${answered:-0}" -ge "$newest" ]; }; then
  # Else a body an earlier run left is opened as this stage's when it writes none.
  rm -f build/pr-body.md
  stage implement
fi
if [ -z "$url" ]; then
  git push -q -u origin "$branch" || fail "git push"
  title=$(gh issue view "$N" --json title --jq .title)
  if [ -s build/pr-body.md ]; then
    body=$(cat build/pr-body.md)
  else
    body="$(sed "s/#NN/#$N/" "$PR_TEMPLATE")

---

Opened by \`pipeline.sh\` with no \`build/pr-body.md\` in the worktree: no implement stage wrote
this body, so the sections above are the template's."
  fi
  url=$(gh pr create --draft --title "NL-$N $title" --body-file - <<<"$body")
  say "opened $url"
fi

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
  # origin/$branch rather than headRefOid (#401) or HEAD, which can carry a commit an earlier run left unpushed.
  before=$(git rev-parse "origin/$branch")
  stage fix "$url" "$round"
  moved=0
  [ "$(git rev-parse HEAD)" = "$before" ] || moved=1
done

stage test "$url"
dirty=$(git status --porcelain)
# HEAD rather than origin/$branch: the hand-off lists what this reset discards, and $dirty holds no commit.
[ -z "$dirty" ] || reset_tree HEAD

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
# Every retry stash still on the worktree, not this run's: a run that stashed and ended on a question posted no hand-off.
stashes=$(git stash list --format=%gs | grep -o "NL-$N retry .*" || true)

gh pr ready "$url"
gh issue edit "$N" --add-label agent/pr-ready >/dev/null
gh issue comment "$N" --body-file - >/dev/null <<BODY
Pull request: $url
Review/fix rounds: $round, $outcome
Cost: $(printf '%.2f' "$cost") USD, wall time: $minutes min, from $results stage results in $LOG.
${stashes:+Uncommitted edits found on pick-up are stashed in the worktree:
\`\`\`
$stashes
\`\`\`}
${dirty:+Tree was dirty after the test stage and was reset with \`reset_tree\` (\`pipeline.sh\`):
\`\`\`
$dirty
\`\`\`}
BODY
echo "$url"
