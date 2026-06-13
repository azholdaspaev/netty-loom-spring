#!/usr/bin/env bash
# Poll a URL until it returns success or a timeout elapses.
# Usage: wait-for-health.sh <url> [timeout_seconds]
set -euo pipefail

URL="${1:?usage: wait-for-health.sh <url> [timeout_seconds]}"
TIMEOUT="${2:-60}"

deadline=$(( $(date +%s) + TIMEOUT ))
until curl -fs "$URL" >/dev/null 2>&1; do
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "wait-for-health: timed out after ${TIMEOUT}s waiting for $URL" >&2
    exit 1
  fi
  sleep 0.5
done
echo "wait-for-health: $URL is up"
