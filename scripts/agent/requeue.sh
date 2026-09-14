#!/usr/bin/env bash
# Move every agent/needs-input issue whose question the repository owner has answered back to
# agent/queued -- or, when an agent/fix pull request is open on the issue's branch, so the runner
# will rerun its stages, take agent/needs-input off alone -- and label agent/needs-input every
# agent/pr-ready issue whose newest comment is a question: the runner's agent/fix stages post
# nothing on the issue, so one they asked and could not read back (stage.sh) stays newest; on the
# pipeline path the hand-over or failure comment follows it, and the maintainer reads it there.
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
  [ "$answered" = true ] || continue
  fixing=$(gh pr list --label agent/fix --state open --json headRefName \
    --jq "any(.[]; .headRefName | startswith(\"NL-$n-\"))")
  if [ "$fixing" = true ]; then
    gh issue edit "$n" --remove-label agent/needs-input
  else
    gh issue edit "$n" --remove-label agent/needs-input --add-label agent/queued
  fi
done

for n in $(gh issue list --label agent/pr-ready --state open --json number --jq '.[].number'); do
  pending=$(comments "$n" | jq -r --arg me "$me" --arg marker "$MARKER" '
        .[-1] | .user.login == $me and (.body | startswith($marker))')
  if [ "$pending" = true ]; then
    gh issue edit "$n" --add-label agent/needs-input
  fi
done
