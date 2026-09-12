#!/usr/bin/env bash
# Move every agent/needs-input issue whose question the repository owner has answered back to
# agent/queued. Usage: scripts/agent/requeue.sh    (one tick; cwd = any checkout of the repository)
set -euo pipefail

MARKER='<!-- agent:question -->'
repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
owner=${repo%%/*}
me=$(gh api user --jq .login)

for n in $(gh issue list --label agent/needs-input --state open --json number --jq '.[].number'); do
  answered=$(gh api --paginate "repos/$repo/issues/$n/comments?per_page=100" | jq -s 'add // []' \
    | jq -r --arg me "$me" --arg owner "$owner" --arg marker "$MARKER" '
        (map(.user.login == $me and (.body | startswith($marker))) | rindex(true)) as $question
        | $question != null and $question < length - 1 and .[-1].user.login == $owner')
  if [ "$answered" = true ]; then
    gh issue edit "$n" --remove-label agent/needs-input --add-label agent/queued
  fi
done
