#!/usr/bin/env bash
# One launchd tick of the agent pipeline, under a lock: fail every issue a dead tick left on
# agent/running and strip agent/* labels from closed issues, drop the worktree, branch and agent/*
# labels of every merged pull request, requeue answered questions, run a fix stage then a review
# stage per agent/fix pull request, then take the oldest agent/queued issue to a worktree of its
# own and run pipeline.sh there; its first infrastructure failure goes back to agent/queued with
# agent/retried, any other to agent/failed.
# Usage: scripts/agent/runner.sh    (any cwd; the main clone is this script's grandparent)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MAIN="$(cd "$HERE/../.." && pwd)"
WT="$(cd "$MAIN/.." && pwd)/netty-loom-wt"
STATE="$HOME/.netty-loom-agent"
TAIL=30

mkdir -p "$STATE"
exec 9>"$STATE/runner.lock"
flock -n 9 || exit 0
cd "$MAIN"
git fetch -q origin

issue_of() { local rest=${1#NL-}; echo "${rest%%-*}"; }
log_of() { local log="$STATE/logs/NL-$1/runner.log"; mkdir -p "${log%/*}"; touch "$log"; echo "$log"; }
failure() { cat <<BODY
$1. Last $TAIL lines of stderr, full log at \`$2\`:
\`\`\`
$(tail -n "$TAIL" "$2")
\`\`\`
BODY
}
RETRY='Replace `agent/failed` with `agent/queued` to retry from the worktree as it is.'

# --- sweep ---
# The lock held above proves no pipeline is running, so agent/running on an open issue is a tick
# that died without reaching its own label handling; beside agent/pr-ready it died after
# pipeline.sh had handed over, beside agent/queued the maintainer has already requeued it, and
# either way only the label is stale.
gh issue list --label agent/running --state open --json number,labels \
  --jq '.[] | "\(.number) \(any(.labels[]; .name == "agent/pr-ready" or .name == "agent/queued"))"' \
| while read -r n settled; do
  if [ "$settled" = true ]; then
    gh issue edit "$n" --remove-label agent/running >/dev/null
    continue
  fi
  gh issue edit "$n" --remove-label agent/running --add-label agent/failed >/dev/null
  { failure 'A tick died with this issue on `agent/running`' "$(log_of "$n")"
    echo "$RETRY"
  } | gh issue comment "$n" --body-file - >/dev/null
done
# The search index lags the issue, so state and labels come from the issue itself.
agent_labels=$(gh label list --search agent/ --json name --jq '[.[].name | select(startswith("agent/"))] | join(",")')
gh issue list --state closed --search "label:$agent_labels" --json number --jq '.[].number' \
| while read -r n; do
  labels=$(gh issue view "$n" --json state,labels \
    --jq 'select(.state == "CLOSED") | [.labels[].name | select(startswith("agent/"))] | join(",")')
  [ -z "$labels" ] || gh issue edit "$n" --remove-label "$labels" >/dev/null
done

# --- merged ---
for wt in "$WT"/NL-*/; do
  [ -d "$wt" ] || continue
  branch=$(basename "$wt")
  [ "$(gh pr list --head "$branch" --state merged --json number --jq length)" != 0 ] || continue
  git worktree remove --force "$wt"
  git branch -q -D "$branch"
  n=$(issue_of "$branch")
  labels=$(gh issue view "$n" --json labels --jq '[.labels[].name | select(startswith("agent/"))] | join(",")')
  [ -z "$labels" ] || gh issue edit "$n" --remove-label "$labels" >/dev/null
done

"$HERE/requeue.sh"

# --- fix ---
gh pr list --label agent/fix --state open --json url,headRefName --jq '.[] | "\(.url) \(.headRefName)"' \
| while read -r url branch; do
  n=$(issue_of "$branch")
  log=$(log_of "$n")
  [ -d "$WT/$branch" ] || git worktree add -q "$WT/$branch" "$branch"
  rc=0
  for stage in fix review; do
    (cd "$WT/$branch" && "$HERE/stage.sh" "$n" "$stage" "$url") </dev/null 2>>"$log" || { rc=$?; break; }
  done
  if [ "$rc" = 0 ]; then
    gh pr edit "$url" --remove-label agent/fix >/dev/null
  else
    gh pr edit "$url" --remove-label agent/fix --add-label agent/failed >/dev/null
    failure "The $stage stage failed (exit $rc)" "$log" | gh pr comment "$url" --body-file - >/dev/null
  fi
done

# --- queued ---
n=$(gh issue list --label agent/queued --state open --json number --jq 'min_by(.number).number // empty')
[ -n "$n" ] || exit 0
slug=$(gh issue view "$n" --json title --jq .title | tr 'A-Z' 'a-z' | sed -E 's/[^a-z0-9]+/-/g; s/^-//; s/-$//' | cut -d- -f1-3)
branch="NL-$n-$slug"
log=$(log_of "$n")
# A stale agent/pr-ready (failed at hand-over, or its pull request closed unmerged) comes off
# here, so beside agent/running it means the pipeline finished, as the sweep reads it.
stale=$(gh issue view "$n" --json labels --jq '.labels[].name | select(. == "agent/pr-ready")')
gh issue edit "$n" --remove-label "agent/queued${stale:+,$stale}" --add-label agent/running >/dev/null
[ -d "$WT/$branch" ] || git worktree add -q "$WT/$branch" -b "$branch" origin/main
rc=0
(cd "$WT/$branch" && { ./gradlew dependencySources || exit 2; } && "$HERE/pipeline.sh" "$n") 2>>"$log" || rc=$?
if [ "$rc" = 0 ]; then
  gh issue edit "$n" --remove-label agent/running >/dev/null
  exit 0
fi
# stage.sh's codes, passed through by pipeline.sh: 124 from timeout, 2 for a gh call that failed
# (pipeline.sh's own too) or claude's error_during_execution; anything else is the work.
class=work; case "$rc" in 124|2) class=infrastructure ;; esac
retried=$(gh issue view "$n" --json labels --jq '.labels[].name | select(. == "agent/retried")')
if [ "$class" = infrastructure ] && [ -z "$retried" ]; then
  gh issue edit "$n" --remove-label agent/running --add-label agent/queued,agent/retried >/dev/null
else
  gh issue edit "$n" --remove-label agent/running --add-label agent/failed >/dev/null
  { failure "Pipeline failed (exit $rc, $class${retried:+, retried once already})" "$log"
    echo "$RETRY"
  } | gh issue comment "$n" --body-file - >/dev/null
fi
