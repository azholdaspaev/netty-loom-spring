#!/usr/bin/env bash
# Run one target under one k6 scenario and capture what run-all.sh's memory-per-connection figure
# is made of: an NMT diff, a heap summary and a thread census at the plateau and again after the
# connections close; with PROFILE=1, also a JFR recording, a virtual-thread dump and a class
# histogram. Artifact names follow run-all.sh, so summarize.py renders the run directory as-is.
#
# Knobs (env vars):
#   VUS, DURATION, RAMP, JAVA_FLAGS, SETTLE   as run-all.sh
#   PROFILE      1 adds JFR, the virtual-thread dump and the histograms  (default 0)
#   CAPTURE_AT   seconds after k6 starts at which the plateau is captured (default 60)
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
CAPTURE_AT="${CAPTURE_AT:-60}"
REPO_ROOT="${REPO_ROOT:-$(cd "$BENCH_DIR/.." && pwd)}"
BASE="http://localhost:${PORT}"

mkdir -p "$RUN"
"$SCRIPT_DIR/collect-env.sh" "$RUN" "$REPO_ROOT" > /dev/null

SERVER_PID=""
SAMPLER_PID=""
cleanup() {
  [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" 2>/dev/null || true
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

"$SCRIPT_DIR/sample-memory.sh" "$SERVER_PID" "$RUN/${NAME}_idle.csv" 1 6
jcmd "$SERVER_PID" VM.native_memory baseline > /dev/null
jcmd "$SERVER_PID" VM.native_memory summary > "$RUN/nmt-idle.txt"
if [ "$PROFILE" = 1 ]; then
  # The two lifecycle events fire once per request; at this load they would swamp the file.
  jcmd "$SERVER_PID" JFR.start name=plateau settings=profile \
    jdk.VirtualThreadStart#enabled=false jdk.VirtualThreadEnd#enabled=false > /dev/null
fi

"$SCRIPT_DIR/sample-memory.sh" "$SERVER_PID" "$RUN/${NAME}_high_load.csv" 2 100000 &
SAMPLER_PID=$!
k6 run --quiet --env BASE_URL="$BASE" --env VUS="$VUS" --env DURATION="$DURATION" --env RAMP="$RAMP" \
  --summary-export "$RUN/${NAME}_high.summary.json" \
  "$BENCH_DIR/k6/$K6_SCRIPT" > "$RUN/${NAME}_high.k6.log" 2>&1 &
K6_PID=$!

sleep "$CAPTURE_AT"
echo "$NAME: capturing plateau at ${CAPTURE_AT}s"
established > "$RUN/netstat-plateau.txt"
jcmd "$SERVER_PID" VM.native_memory summary.diff > "$RUN/nmt-plateau.diff.txt"
jcmd "$SERVER_PID" GC.heap_info > "$RUN/heap-plateau.txt"
jcmd "$SERVER_PID" Thread.print > "$RUN/threads-plateau.txt"
if [ "$PROFILE" = 1 ]; then
  jcmd "$SERVER_PID" Thread.dump_to_file -format=text -overwrite "$RUN/vthreads-plateau.txt" > /dev/null
  jcmd "$SERVER_PID" JFR.dump name=plateau filename="$RUN/plateau.jfr" path-to-gc-roots=true > /dev/null
  # Last, and only here: the histogram forces a full GC, which moves the RSS a plain run reports.
  jcmd "$SERVER_PID" GC.class_histogram > "$RUN/histogram-plateau.txt"
fi

rc=0
wait "$K6_PID" || rc=$?
printf '%s\n' "$rc" > "$RUN/${NAME}_high.exit"
kill "$SAMPLER_PID" 2>/dev/null || true
SAMPLER_PID=""
echo "$NAME: k6 exited $rc"

sleep 5
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
