#!/usr/bin/env bash
# Move every agent/needs-input issue whose question the repository owner has answered back to
# agent/queued, and label agent/needs-input every agent/pr-ready issue whose newest comment is a
# question: the runner's agent/fix stages post nothing on the issue, so one they asked and could
# not read back (stage.sh) stays newest; on the pipeline path the hand-over or failure comment
# follows it, and the maintainer reads it there.
# Usage: scripts/agent/requeue.sh    (one tick; cwd = any checkout of the repository)
set -euo pipefail

MARKER='<!-- agent:question -->'
repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
owner=${repo%%/*}
me=$(gh api user --jq .login)
comments() { gh api --paginate "repos/$repo/issues/$1/comments?per_page=100" | jq -s 'add // []'; }

for n in $(gh issue list --label agent/needs-input --state open --json number --jq '.[].number'); do
  answered=$(comments "$n" | jq -r --arg me "$me" --arg owner "$owner" --arg marker "$MARKER" '
        (map(.user.login == $me and (.body | startswith($marker))) | rindex(true)) as $question
        | $question != null and $question < length - 1 and .[-1].user.login == $owner')
  if [ "$answered" = true ]; then
    gh issue edit "$n" --remove-label agent/needs-input --add-label agent/queued
  fi
done

# Only the newest comment counts: the runner's own hand-over or failure comment follows an
# answered question just as an answer does, and both come from the owner's login.
for n in $(gh issue list --label agent/pr-ready --state open --json number --jq '.[].number'); do
  pending=$(comments "$n" | jq -r --arg me "$me" --arg marker "$MARKER" '
        .[-1] | .user.login == $me and (.body | startswith($marker))')
  if [ "$pending" = true ]; then
    gh issue edit "$n" --add-label agent/needs-input
  fi
done
