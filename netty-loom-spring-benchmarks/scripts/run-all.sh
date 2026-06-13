#!/usr/bin/env bash
# Orchestrate the three-way benchmark: for each target, start it with identical JVM flags,
# wait for health, sample idle memory, run the low- and high-concurrency k6 scenarios while
# sampling memory under load, then tear it down before starting the next. Finally render a
# committed Markdown snapshot.
#
# Targets:
#   netty-loom        :18080  (this library's starter)
#   tomcat-platform   :18081  (spring.threads.virtual.enabled=false)
#   tomcat-virtual    :18082  (spring.threads.virtual.enabled=true)
#
# Knobs (env vars):
#   VUS        concurrent connections for the high-concurrency run   (default 10000)
#   DURATION   steady-state plateau                                   (default 60s)
#   RAMP       warmup ramp to VUS                                     (default 15s)
#   JAVA_FLAGS JVM flags applied IDENTICALLY to all three targets
#
# Usage:  ./run-all.sh
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
BENCH_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
REPO_ROOT=$(cd "$BENCH_DIR/.." && pwd)
RESULTS="$BENCH_DIR/results"
K6_DIR="$BENCH_DIR/k6"
mkdir -p "$RESULTS"

VUS="${VUS:-10000}"
DURATION="${DURATION:-60s}"
RAMP="${RAMP:-15s}"
# Seconds to settle between targets. After a high-VU run, ~VUS client sockets enter TIME_WAIT
# (~30s on macOS); on a single loopback box this must drain before the next target claims its
# ephemeral ports, or connections fail spuriously. Bump for high VUS.
SETTLE="${SETTLE:-10}"
# Identical flags for every target so the memory comparison is apples-to-apples. No -Xms, so
# committed heap (and thus RSS) grows with real usage rather than being pre-committed away.
JAVA_FLAGS="${JAVA_FLAGS:--XX:+UseG1GC -Xmx2g -XX:NativeMemoryTracking=summary}"

# Resolve the bootJars by glob so a version bump doesn't silently break the run. The plain
# (-plain.jar) artifact is filtered out; the boot-packaged jar is the runnable one.
NETTY_JAR=$(ls "$REPO_ROOT"/netty-loom-spring-example-netty/build/libs/*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -1 || true)
TOMCAT_JAR=$(ls "$REPO_ROOT"/netty-loom-spring-example-tomcat/build/libs/*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -1 || true)

if [ -z "$NETTY_JAR" ] || [ -z "$TOMCAT_JAR" ]; then
  echo "Missing bootJars. Build them first:" >&2
  echo "  (cd $REPO_ROOT && ./gradlew :netty-loom-spring-example-netty:bootJar :netty-loom-spring-example-tomcat:bootJar)" >&2
  exit 1
fi

SERVER_PID=""
SAMPLER_PID=""
cleanup() { kill "$SAMPLER_PID" "$SERVER_PID" 2>/dev/null || true; }
trap cleanup EXIT

benchmark_target() {
  local name="$1" port="$2"; shift 2
  local base="http://localhost:${port}"
  echo "================ $name ($base) ================"

  # shellcheck disable=SC2086
  java $JAVA_FLAGS "$@" > "$RESULTS/${name}.server.log" 2>&1 &
  SERVER_PID=$!
  echo "  started pid=$SERVER_PID with flags: $JAVA_FLAGS"

  "$SCRIPT_DIR/wait-for-health.sh" "$base/ping" 90

  echo "  sampling idle memory..."
  "$SCRIPT_DIR/sample-memory.sh" "$SERVER_PID" "$RESULTS/${name}_idle.csv" 1 6

  echo "  scenario 1: low-concurrency..."
  k6 run --quiet --env BASE_URL="$base" \
    --summary-export "$RESULTS/${name}_low.summary.json" \
    "$K6_DIR/low-concurrency.js" > "$RESULTS/${name}_low.k6.log" 2>&1 \
    || echo "  (k6 reported a threshold breach in low-concurrency for $name — recorded, continuing)"

  echo "  scenario 2: high-concurrency (VUS=$VUS, $DURATION)..."
  "$SCRIPT_DIR/sample-memory.sh" "$SERVER_PID" "$RESULTS/${name}_high_load.csv" 2 100000 &
  SAMPLER_PID=$!
  k6 run --quiet --env BASE_URL="$base" --env VUS="$VUS" --env DURATION="$DURATION" --env RAMP="$RAMP" \
    --summary-export "$RESULTS/${name}_high.summary.json" \
    "$K6_DIR/high-concurrency.js" > "$RESULTS/${name}_high.k6.log" 2>&1 \
    || echo "  (k6 reported a threshold breach in high-concurrency for $name — recorded, continuing)"
  kill "$SAMPLER_PID" 2>/dev/null || true
  SAMPLER_PID=""

  echo "  tearing down pid=$SERVER_PID..."
  kill "$SERVER_PID" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
  SERVER_PID=""
  # wait for the port to be released, then let client-side TIME_WAIT sockets drain before
  # the next target competes for ephemeral ports.
  for _ in $(seq 1 60); do curl -fs "$base/ping" >/dev/null 2>&1 || break; sleep 0.5; done
  echo "  settling ${SETTLE}s..."
  sleep "$SETTLE"
}

benchmark_target "netty-loom"      18080 -jar "$NETTY_JAR"
benchmark_target "tomcat-platform" 18081 -jar "$TOMCAT_JAR" --spring.profiles.active=platform
benchmark_target "tomcat-virtual"  18082 -jar "$TOMCAT_JAR" --spring.profiles.active=virtual

echo "================ summarizing ================"
UNAME=$(uname -srm)
python3 "$SCRIPT_DIR/summarize.py" "$RESULTS" "$VUS" "$JAVA_FLAGS" "$UNAME" > "$RESULTS/SNAPSHOT.md"
echo "wrote $RESULTS/SNAPSHOT.md"
