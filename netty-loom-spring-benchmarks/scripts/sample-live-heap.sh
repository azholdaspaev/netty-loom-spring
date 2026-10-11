#!/usr/bin/env bash
# Sample a JVM's retained state: live heap after a forced full collection, the virtual threads alive
# at that instant, and NMT's committed `Other` (Netty's pooled direct buffers). summarize.py fits
# live heap against virtual threads to split what the connections retain from what the in-flight
# requests do. Writes CSV to stdout, so run-all.sh can stream it back over ssh.
#
# `jcmd GC.class_histogram` forces the full collection, so its total is live bytes and every sample
# pauses the JVM: never run this inside a scenario whose latency is published, nor just before one.
# --nmt-only skips it and leaves the heap columns blank.
#
# Usage: sample-live-heap.sh <pid> [count] [interval_seconds] [--nmt-only]
set -euo pipefail

PID="${1:?usage: sample-live-heap.sh <pid> [count] [interval] [--nmt-only]}"
COUNT="${2:-1}"
INTERVAL="${3:-1}"
NMT_ONLY="${4:-}"

echo "ts_epoch,live_bytes,virtual_threads,other_committed_kb"

for i in $(seq 1 "$COUNT"); do
  ts=$(date +%s)
  live="" vts=""
  if [ "$NMT_ONLY" != --nmt-only ]; then
    read -r live vts < <(jcmd "$PID" GC.class_histogram |
      awk '$1 == "Total" { live = $3 } $4 == "java.lang.VirtualThread" { vts = $2 }
           END { print live, vts + 0 }')
  fi
  # Blank when NMT is off: jcmd then prints "Native memory tracking is not enabled".
  other=$(jcmd "$PID" VM.native_memory summary |
    sed -n 's/.* Other (reserved=[0-9]*KB, committed=\([0-9]*\)KB).*/\1/p')
  echo "${ts},${live},${vts},${other}"
  if [ "$i" -lt "$COUNT" ]; then sleep "$INTERVAL"; fi
done
