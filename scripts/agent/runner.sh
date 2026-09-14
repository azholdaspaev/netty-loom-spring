#!/usr/bin/env bash
# One launchd tick of the agent pipeline, under a lock: fail every issue a dead tick left on
# agent/running and strip agent/* labels from closed issues, drop the worktree, branch and agent/*
# labels of every merged pull request, requeue answered questions, run a fix stage then a review
# stage per agent/fix pull request whose issue is open and not on agent/needs-input (a question
# from either stage puts it there, and the label stays on the pull request; an issue closed,
# unreadable or not named by the branch fails the pull request), then take the oldest agent/queued
# issue to a worktree of its own and run pipeline.sh there; its first infrastructure failure goes
# back to agent/queued with agent/retried, any other to agent/failed.
# Usage: scripts/agent/runner.sh    (any cwd; the main clone is this script's grandparent)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MAIN="$(cd "$HERE/../.." && pwd)"
WT="$(cd "$MAIN/.." && pwd)/netty-loom-wt"
STATE="$HOME/.netty-loom-agent"
TAIL=30

stamp() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) runner.sh: $*"; }
say() { stamp "$@" >&2; }
note() { stamp "$@" | tee -a "$log" >&2; }

mkdir -p "$STATE"
exec 9>"$STATE/runner.lock"
rc=0; flock -n 9 || rc=$?
case "$rc" in 0) ;; 1) say "tick skipped: lock held"; exit 0 ;; *) say "tick failed: flock exit $rc"; exit "$rc" ;; esac
say "tick start"
trap 'say "tick end (exit $?)"' EXIT
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
# fail_fix <pr url> <log line>: agent/fix off, agent/failed on, and stdin as the comment
fail_fix() { say "fix: $2, agent/failed"; gh pr edit "$1" --remove-label agent/fix --add-label agent/failed >/dev/null; gh pr comment "$1" --body-file - >/dev/null; }

# --- sweep ---
# The lock held above proves no pipeline is running, so agent/running on an open issue is a tick
# that died without reaching its own label handling; beside agent/pr-ready it died after
# pipeline.sh had handed over, beside agent/queued the maintainer has already requeued it, and
# either way only the label is stale.
gh issue list --label agent/running --state open --json number,labels \
  --jq '.[] | "\(.number) \(any(.labels[]; .name == "agent/pr-ready" or .name == "agent/queued"))"' \
| while read -r n settled; do
  if [ "$settled" = true ]; then
    say "sweep: NL-$n agent/running off"
    gh issue edit "$n" --remove-label agent/running >/dev/null
    continue
  fi
  say "sweep: NL-$n agent/running -> agent/failed"
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
  [ -z "$labels" ] || { say "sweep: NL-$n closed, $labels off"; gh issue edit "$n" --remove-label "$labels" >/dev/null; }
done

# --- merged ---
for wt in "$WT"/NL-*/; do
  [ -d "$wt" ] || continue
  branch=$(basename "$wt")
  [ "$(gh pr list --head "$branch" --state merged --json number --jq length)" != 0 ] || continue
  # --force twice: the lock is another tool's (supacode locks every worktree of the clone), and
  # nothing the pipeline owns is behind it once the pull request is merged.
  rc=0; git worktree remove --force --force "$wt" || { rc=$?; say "merged: $branch not removed (git exit $rc)"; continue; }
  say "merged: $branch removed"
  git branch -q -D "$branch"
  n=$(issue_of "$branch")
  labels=$(gh issue view "$n" --json labels --jq '[.labels[].name | select(startswith("agent/"))] | join(",")')
  [ -z "$labels" ] || gh issue edit "$n" --remove-label "$labels" >/dev/null
done

say "requeue"
"$HERE/requeue.sh"

# --- fix ---
gh pr list --label agent/fix --state open --json url,headRefName --jq '.[] | "\(.url) \(.headRefName)"' \
| while read -r url branch; do
  n=$(issue_of "$branch")
  case "$n" in
    ''|*[!0-9]*)
      echo "\`agent/fix\` runs on a branch named \`NL-<issue>-<slug>\`; this one is \`$branch\`." | fail_fix "$url" "$branch names no issue"
      continue ;;
  esac
  log=$(log_of "$n")
  state=$(gh issue view "$n" --json state,labels 2>>"$log" \
    --jq 'if .state == "CLOSED" then "closed" elif any(.labels[]; .name == "agent/needs-input") then "waiting" else "open" end') \
    || state=unreadable
  case "$state" in
    waiting) continue ;;
    closed)
      echo "\`agent/fix\` needs its issue open; #$n is closed." | fail_fix "$url" "NL-$n closed"
      continue ;;
    unreadable)
      failure "Issue #$n, which the branch names, could not be read" "$log" | fail_fix "$url" "NL-$n unreadable"
      continue ;;
  esac
  note "fix: NL-$n $url"
  [ -d "$WT/$branch" ] || git worktree add -q "$WT/$branch" "$branch"
  rc=0
  run_id=$(date -u +%Y%m%dT%H%M%SZ)
  for stage in fix review; do
    (cd "$WT/$branch" && RUN_ID=$run_id "$HERE/stage.sh" "$n" "$stage" "$url") </dev/null 2>>"$log" || { rc=$?; break; }
  done
  case "$rc" in
    0) gh pr edit "$url" --remove-label agent/fix >/dev/null ;;
    3) # agent/fix stays on rather than requeue.sh putting it back with the answer: requeue.sh reads it to tell this question from pipeline.sh's.
       gh issue edit "$n" --add-label agent/needs-input >/dev/null ;;
    *) gh pr edit "$url" --remove-label agent/fix --add-label agent/failed >/dev/null
       failure "The $stage stage failed (exit $rc)" "$log" | gh pr comment "$url" --body-file - >/dev/null ;;
  esac
  note "fix: NL-$n exit $rc"
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
note "queued: NL-$n picked up on $branch"
gh issue edit "$n" --remove-label "agent/queued${stale:+,$stale}" --add-label agent/running >/dev/null
[ -d "$WT/$branch" ] || git worktree add -q "$WT/$branch" -b "$branch" origin/main
rc=0
(cd "$WT/$branch" && { ./gradlew dependencySources || exit 2; } && "$HERE/pipeline.sh" "$n") 2>>"$log" || rc=$?
if [ "$rc" = 0 ]; then
  gh issue edit "$n" --remove-label agent/running >/dev/null
else
  # 124 and 2 are stage.sh's infrastructure codes (its header), passed through by pipeline.sh.
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
fi
note "queued: NL-$n pipeline exit $rc"
