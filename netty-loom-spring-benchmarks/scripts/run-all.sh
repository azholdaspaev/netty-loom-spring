#!/usr/bin/env bash
# Orchestrate the three-way benchmark: for each target, start it with identical JVM flags,
# wait for health, sample idle memory, run the low-, high- and secured high-concurrency k6
# scenarios while sampling memory under load, then tear it down before starting the next. Finally
# render a committed Markdown snapshot.
#
# Targets:
#   netty-loom        :18080  (this library's starter)
#   tomcat-platform   :18081  (spring.threads.virtual.enabled=false)
#   tomcat-virtual    :18082  (spring.threads.virtual.enabled=true)
#
# Knobs (env vars):
#   VUS         concurrent connections for the high-concurrency runs  (default 10000)
#   DURATION    steady-state plateau                                   (default 60s)
#   RAMP        warmup ramp to VUS                                     (default 15s)
#   SETTLE      drain seconds between targets AND between scenarios 2 and 3 (default 10)
#   JAVA_FLAGS  JVM flags applied IDENTICALLY to all three targets
#   RESULTS_DIR where raw artifacts and SNAPSHOT.md are written       (default ./results)
#
# Usage:  ./run-all.sh
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
BENCH_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
REPO_ROOT=$(cd "$BENCH_DIR/.." && pwd)
# Overridable so a versioned sweep (results/v0.5) needs no second script.
RESULTS="${RESULTS_DIR:-$BENCH_DIR/results}"
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
# Tomcat's connector defaults to maxConnections=8192 — an established-connection cap that
# spring.threads.virtual.enabled does NOT raise. At VUS connections, the overflow is reset/
# queued and shows up as a tail-latency + error-rate collapse that's config, not architecture.
# Raise the cap well above the offered load so the comparison reflects architecture. The VT
# target also gets threads.max raised (its executor isn't pool-bounded, but this is explicit).
TOMCAT_MAXCONN=$(( VUS * 2 ))

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

# Run one k6 scenario, recording its exit code beside the summary export. k6 exits 99 when a
# threshold is crossed -- a saturated target is the finding this harness publishes -- and some other
# non-zero code when the run did not finish at all (108 for exec.test.abort(), 107 for a script
# exception). A truncated run still writes a complete-looking summary export, so the exit code is
# the only signal summarize.py has that the numbers in it cover a whole run.
run_scenario() {
  local name="$1" base="$2" scenario="$3" script="$4"; shift 4
  local rc=0
  k6 run --quiet --env BASE_URL="$base" "$@" \
    --summary-export "$RESULTS/${name}_${scenario}.summary.json" \
    "$K6_DIR/$script" > "$RESULTS/${name}_${scenario}.k6.log" 2>&1 || rc=$?
  printf '%s\n' "$rc" > "$RESULTS/${name}_${scenario}.exit"
  [ "$rc" -eq 0 ] || echo "  (k6 exited $rc for ${name}/${scenario} — recorded, continuing)"
}

benchmark_target() {
  local name="$1" port="$2"; shift 2
  local base="http://localhost:${port}"
  local -a load_env=(--env VUS="$VUS" --env DURATION="$DURATION" --env RAMP="$RAMP")
  echo "================ $name ($base) ================"

  # shellcheck disable=SC2086
  java $JAVA_FLAGS "$@" > "$RESULTS/${name}.server.log" 2>&1 &
  SERVER_PID=$!
  echo "  started pid=$SERVER_PID with flags: $JAVA_FLAGS"

  "$SCRIPT_DIR/wait-for-health.sh" "$base/ping" 90

  echo "  sampling idle memory..."
  "$SCRIPT_DIR/sample-memory.sh" "$SERVER_PID" "$RESULTS/${name}_idle.csv" 1 6

  echo "  scenario 1: low-concurrency..."
  run_scenario "$name" "$base" low low-concurrency.js

  echo "  scenario 2: high-concurrency (VUS=$VUS, $DURATION)..."
  "$SCRIPT_DIR/sample-memory.sh" "$SERVER_PID" "$RESULTS/${name}_high_load.csv" 2 100000 &
  SAMPLER_PID=$!
  run_scenario "$name" "$base" high high-concurrency.js "${load_env[@]}"
  kill "$SAMPLER_PID" 2>/dev/null || true
  SAMPLER_PID=""

  # Settle before scenario 3 as well as between targets: scenario 2 leaves ~VUS client sockets in
  # TIME_WAIT, and scenario 3 wants VUS more immediately. Without this the secured run fails on
  # ephemeral-port exhaustion and reads as a Security-chain problem.
  echo "  settling ${SETTLE}s before the secured scenario..."
  sleep "$SETTLE"

  # No memory sampler here, unlike scenario 2: the secured run holds one authenticated session per
  # VU, so RSS growth would be measuring sessions rather than connections and the snapshot declines
  # to publish it. Sampling anyway would mean a jcmd JVM launch every 2s against the process being
  # measured, for a CSV nothing reads.
  echo "  scenario 3: high-concurrency secured (VUS=$VUS, $DURATION)..."
  run_scenario "$name" "$base" secured high-concurrency-secured.js "${load_env[@]}"

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
benchmark_target "tomcat-platform" 18081 -jar "$TOMCAT_JAR" --spring.profiles.active=platform \
  --server.tomcat.max-connections="$TOMCAT_MAXCONN"
benchmark_target "tomcat-virtual"  18082 -jar "$TOMCAT_JAR" --spring.profiles.active=virtual \
  --server.tomcat.max-connections="$TOMCAT_MAXCONN" --server.tomcat.threads.max="$TOMCAT_MAXCONN"

echo "================ summarizing ================"
UNAME=$(uname -srm)
python3 "$SCRIPT_DIR/summarize.py" "$RESULTS" "$VUS" "$JAVA_FLAGS" "$UNAME" "$TOMCAT_MAXCONN" > "$RESULTS/SNAPSHOT.md"
echo "wrote $RESULTS/SNAPSHOT.md"
