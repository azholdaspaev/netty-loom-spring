#!/usr/bin/env bash
# Sample a JVM's resident memory (and, best-effort, its used heap) at a fixed interval.
#
# This is the server-side half of the benchmark: k6 only sees client-side latency/throughput,
# so memory-per-connection has to be measured here. RSS is the honest "total footprint" figure
# (committed heap + thread stacks + Netty direct buffers + JVM overhead). jcmd GC.heap_info adds
# the live-heap breakdown, which is where virtual-thread continuations live — so comparing RSS
# growth against heap growth shows whether a target pays per connection in native stacks
# (platform threads) or cheap on-heap continuations (virtual threads).
#
# Usage: sample-memory.sh <pid> <output_csv> [interval_seconds] [max_samples]
set -euo pipefail

PID="${1:?usage: sample-memory.sh <pid> <output_csv> [interval] [max_samples]}"
OUT="${2:?usage: sample-memory.sh <pid> <output_csv> [interval] [max_samples]}"
INTERVAL="${3:-2}"
MAX="${4:-100000}"

echo "ts_epoch,rss_kb,heap_used_kb" > "$OUT"

i=0
while kill -0 "$PID" 2>/dev/null && [ "$i" -lt "$MAX" ]; do
  rss=$(ps -o rss= -p "$PID" 2>/dev/null | tr -d ' ' || true)
  # Best-effort heap parse; format varies by GC, so tolerate failure (blank column).
  heap=$(jcmd "$PID" GC.heap_info 2>/dev/null | sed -n 's/.*used \([0-9]*\)K.*/\1/p' | head -1 || true)
  echo "$(date +%s),${rss:-},${heap:-}" >> "$OUT"
  i=$((i + 1))
  sleep "$INTERVAL"
done
