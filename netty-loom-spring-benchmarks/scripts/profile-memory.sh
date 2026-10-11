#!/usr/bin/env bash
# Run one target under one k6 scenario as run-all.sh's memory pass, after its scenario 1:
# sample-live-heap.sh's retained state before load, at the plateau and after the connections close,
# plus an NMT diff, a heap summary and a thread census at the plateau and after close; with
# PROFILE=1, also a virtual-thread dump, a JFR recording and class histograms. Artifact names follow
# run-all.sh's memory pass, so summarize.py renders the run directory's memory row as-is.
#
# Knobs (env vars):
#   VUS, DURATION, RAMP, JAVA_FLAGS, SETTLE   as run-all.sh
#   PROFILE      1 adds JFR, the virtual-thread dump and the histograms  (default 0)
#   CAPTURE_AT   seconds after k6 starts at which the plateau is captured (default 45)
#   REPO_ROOT    checkout the jar was built from, for env-server.txt     (default this repo)
#
# Usage:  ./profile-memory.sh <name> <port> <run_dir> <k6_script> [java args...]
#   e.g.  ./profile-memory.sh netty-loom 18080 /tmp/run high-concurrency.js -jar app.jar
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
BENCH_DIR=$(cd "$SCRIPT_DIR/.." && pwd)

NAME="${1:?usage: profile-memory.sh <name> <port> <run_dir> <k6_script> [java args...]}"
PORT="${2:?usage: profile-memory.sh <name> <port> <run_dir> <k6_script> [java args...]}"
RUN="${3:?usage: profile-memory.sh <name> <port> <run_dir> <k6_script> [java args...]}"
K6_SCRIPT="${4:?usage: profile-memory.sh <name> <port> <run_dir> <k6_script> [java args...]}"
shift 4

VUS="${VUS:-10000}"
DURATION="${DURATION:-60s}"
RAMP="${RAMP:-15s}"
SETTLE="${SETTLE:-25}"
JAVA_FLAGS="${JAVA_FLAGS:--XX:+UseG1GC -Xmx2g -XX:NativeMemoryTracking=summary}"
PROFILE="${PROFILE:-0}"
CAPTURE_AT="${CAPTURE_AT:-45}"
. "$SCRIPT_DIR/capture-window.sh"
REPO_ROOT="${REPO_ROOT:-$(cd "$BENCH_DIR/.." && pwd)}"
BASE="http://localhost:${PORT}"

mkdir -p "$RUN"
"$SCRIPT_DIR/collect-env.sh" "$RUN" "$REPO_ROOT" > /dev/null

SERVER_PID=""
cleanup() {
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT

established() {
  netstat -an -p tcp | awk -v p="\\.${PORT}\$" '$4 ~ p && $6 == "ESTABLISHED"' | wc -l | tr -d ' '
}

# shellcheck disable=SC2086
java $JAVA_FLAGS "$@" > "$RUN/server.log" 2>&1 &
SERVER_PID=$!
echo "$NAME: started pid=$SERVER_PID with flags: $JAVA_FLAGS"
"$SCRIPT_DIR/wait-for-health.sh" "$BASE/ping" 90
# As in run-all.sh, so the pool baseline already holds what the first requests allocated.
k6 run --quiet --env BASE_URL="$BASE" "$BENCH_DIR/k6/low-concurrency.js" > "$RUN/${NAME}_low.k6.log" 2>&1 || true

"$SCRIPT_DIR/sample-live-heap.sh" "$SERVER_PID" 1 1 --nmt-only > "$RUN/${NAME}_retained_preload.csv"
jcmd "$SERVER_PID" VM.native_memory baseline > /dev/null
jcmd "$SERVER_PID" VM.native_memory summary > "$RUN/nmt-idle.txt"
if [ "$PROFILE" = 1 ]; then
  # The two lifecycle events fire once per request; at this load they would swamp the file.
  jcmd "$SERVER_PID" JFR.start name=plateau settings=profile \
    jdk.VirtualThreadStart#enabled=false jdk.VirtualThreadEnd#enabled=false > /dev/null
fi

START=$SECONDS
k6 run --quiet --env BASE_URL="$BASE" --env VUS="$VUS" --env DURATION="$DURATION" --env RAMP="$RAMP" \
  --summary-export "$RUN/${NAME}_memory.summary.json" \
  "$BENCH_DIR/k6/$K6_SCRIPT" > "$RUN/${NAME}_memory.k6.log" 2>&1 &
K6_PID=$!

sleep "$CAPTURE_AT"
echo "$NAME: capturing plateau at ${CAPTURE_AT}s"
if [ "$PROFILE" = 1 ]; then
  # First, so no other capture's pause has just released a wave of expired sleeps into it. Still
  # not an in-flight count: the dump visits each virtual thread in turn, ~0.5 s at 10,000 VUs, and
  # a 50 ms sleep ends first. The retained capture's virtual_threads column is the instantaneous one.
  date +%s > "$RUN/vthreads-plateau.epoch"
  jcmd "$SERVER_PID" Thread.dump_to_file -format=text -overwrite "$RUN/vthreads-plateau.txt" > /dev/null
fi
"$SCRIPT_DIR/sample-live-heap.sh" "$SERVER_PID" 5 1 > "$RUN/${NAME}_retained_plateau.csv"
discard_past_hold "$RUN/${NAME}_retained_plateau.csv" "$START"
established > "$RUN/netstat-plateau.txt"
# Post-collection from here on: sample-live-heap.sh has just forced full GCs.
jcmd "$SERVER_PID" VM.native_memory summary.diff > "$RUN/nmt-plateau.diff.txt"
jcmd "$SERVER_PID" GC.heap_info > "$RUN/heap-plateau.txt"
jcmd "$SERVER_PID" Thread.print > "$RUN/threads-plateau.txt"
if [ "$PROFILE" = 1 ]; then
  jcmd "$SERVER_PID" JFR.dump name=plateau filename="$RUN/plateau.jfr" path-to-gc-roots=true > /dev/null
  jcmd "$SERVER_PID" GC.class_histogram > "$RUN/histogram-plateau.txt"
fi

rc=0
wait "$K6_PID" || rc=$?
printf '%s\n' "$rc" > "$RUN/${NAME}_memory.exit"
echo "$NAME: k6 exited $rc"

sleep 5
"$SCRIPT_DIR/sample-live-heap.sh" "$SERVER_PID" 1 > "$RUN/${NAME}_retained_after.csv"
established > "$RUN/netstat-after.txt"
jcmd "$SERVER_PID" VM.native_memory summary.diff > "$RUN/nmt-after-close.diff.txt"
jcmd "$SERVER_PID" GC.heap_info > "$RUN/heap-after-close.txt"
if [ "$PROFILE" = 1 ]; then
  jcmd "$SERVER_PID" GC.class_histogram > "$RUN/histogram-after-close.txt"
  for view in gc allocation-by-class allocation-by-site memory-leaks-by-class memory-leaks-by-site \
              native-memory-committed pinned-threads thread-allocation; do
    jfr view "$view" "$RUN/plateau.jfr" > "$RUN/jfr-$view.txt" 2>&1 || true
  done
fi

kill "$SERVER_PID" 2>/dev/null || true
wait "$SERVER_PID" 2>/dev/null || true
SERVER_PID=""
grep -cE 'WARN|ERROR' "$RUN/server.log" > "$RUN/server.warn-count.txt" || true
for _ in $(seq 1 60); do curl -fs "$BASE/ping" >/dev/null 2>&1 || break; sleep 0.5; done
echo "$NAME: done, settling ${SETTLE}s"
sleep "$SETTLE"
