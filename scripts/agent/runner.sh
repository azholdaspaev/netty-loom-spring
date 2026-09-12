#!/usr/bin/env bash
# One launchd tick of the agent pipeline: under a lock, requeue answered questions, then take the
# oldest agent/queued issue to a worktree of its own and run pipeline.sh there.
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

"$HERE/requeue.sh"

# --- queued ---
n=$(gh issue list --label agent/queued --state open --json number --jq 'min_by(.number).number // empty')
[ -n "$n" ] || exit 0
slug=$(gh issue view "$n" --json title --jq .title | tr 'A-Z' 'a-z' | sed -E 's/[^a-z0-9]+/-/g; s/^-//; s/-$//' | cut -d- -f1-3)
branch="NL-$n-$slug"
log="$STATE/logs/NL-$n/runner.log"
mkdir -p "$(dirname "$log")"
gh issue edit "$n" --remove-label agent/queued --add-label agent/running >/dev/null
[ -d "$WT/$branch" ] || git worktree add -q "$WT/$branch" -b "$branch" origin/main
rc=0
(cd "$WT/$branch" && ./gradlew dependencySources && "$HERE/pipeline.sh" "$n") 2>>"$log" || rc=$?
if [ "$rc" = 0 ]; then
  gh issue edit "$n" --remove-label agent/running >/dev/null
else
  gh issue edit "$n" --remove-label agent/running --add-label agent/failed >/dev/null
  gh issue comment "$n" --body-file - >/dev/null <<BODY
Pipeline failed (exit $rc). Last $TAIL lines of stderr, full log at \`$log\`:
\`\`\`
$(tail -n "$TAIL" "$log")
\`\`\`
Replace \`agent/failed\` with \`agent/queued\` to retry from the worktree as it is.
BODY
fi
