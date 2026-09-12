#!/usr/bin/env bash
# Take one GitHub issue through the pipeline's stages and set the issue's outcome label.
# Usage: scripts/agent/pipeline.sh <issue number>    (cwd = the issue's worktree)
set -euo pipefail

N="${1:?usage: pipeline.sh <issue number>}"
HERE="$(cd "$(dirname "$0")" && pwd)"

rc=0
url=$("$HERE/stage.sh" "$N" implement) || rc=$?
case "$rc" in
  0) echo "$url" ;;
  3) gh issue edit "$N" --remove-label agent/running --add-label agent/needs-input ;;
  *) exit "$rc" ;;
esac
