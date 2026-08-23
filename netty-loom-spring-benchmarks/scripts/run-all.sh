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
#   ORDER       forward | reversed — the sequence targets are run in    (default forward)
#   REMOTE_HOST ssh destination running the server JVMs; unset = this box (default unset)
#   REMOTE_REPO the repo checkout on REMOTE_HOST, holding the bootJars  (default netty-loom-spring)
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
# Validated rather than defaulted: a typo silently running a third forward pass would be read as a
# crossover control that had agreed, which is worse than no crossover at all.
ORDER="${ORDER:-forward}"
case "$ORDER" in forward|reversed) ;; *) echo "ORDER must be forward or reversed, got '$ORDER'" >&2; exit 1 ;; esac

# When REMOTE_HOST is set the server JVMs run there and only k6 runs here. Everything touching the
# server process -- launch, RSS/CPU sampling, teardown -- then crosses the link, so the connection
# is multiplexed: sample-memory.sh samples every 2s for the whole plateau, and paying an SSH
# handshake per sample would perturb the measurement it is taking.
REMOTE_HOST="${REMOTE_HOST:-}"
REMOTE_REPO="${REMOTE_REPO:-netty-loom-spring}"
SSH=(ssh -o BatchMode=yes -o ControlMaster=auto
     -o ControlPath="${TMPDIR:-/tmp}/nls-bench-%r@%h:%p" -o ControlPersist=300)
SERVER_HOST=localhost
if [ -n "$REMOTE_HOST" ]; then
  SERVER_HOST="${REMOTE_HOST#*@}"
  "${SSH[@]}" "$REMOTE_HOST" true   # open the master now, not mid-measurement
fi

# Resolve the bootJars by glob so a version bump doesn't silently break the run. The plain
# (-plain.jar) artifact is filtered out; the boot-packaged jar is the runnable one.
JAR_ROOT="$REPO_ROOT"
[ -n "$REMOTE_HOST" ] && JAR_ROOT="$REMOTE_REPO"
find_jar() {
  local cmd="ls $JAR_ROOT/$1/build/libs/*.jar 2>/dev/null | grep -v -- '-plain\.jar\$' | awk 'NR==1'"
  if [ -n "$REMOTE_HOST" ]; then "${SSH[@]}" "$REMOTE_HOST" "$cmd"; else eval "$cmd"; fi
}
NETTY_JAR=$(find_jar netty-loom-spring-example-netty || true)
TOMCAT_JAR=$(find_jar netty-loom-spring-example-tomcat || true)

if [ -z "$NETTY_JAR" ] || [ -z "$TOMCAT_JAR" ]; then
  echo "Missing bootJars. Build them first:" >&2
  echo "  (cd $JAR_ROOT && ./gradlew :netty-loom-spring-example-netty:bootJar :netty-loom-spring-example-tomcat:bootJar)" >&2
  exit 1
fi

SERVER_PID=""
SAMPLER_PID=""

# SERVER_PID lives in REMOTE_HOST's PID namespace when one is set, so it must never be passed to a
# local kill -- that number belongs to an unrelated process here.
stop_server() {
  [ -n "$SERVER_PID" ] || return 0
  if [ -n "$REMOTE_HOST" ]; then
    "${SSH[@]}" "$REMOTE_HOST" "kill $SERVER_PID 2>/dev/null || true" || true
  else
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  SERVER_PID=""
}

# One ssh invocation streams the whole CSV back, rather than one per sample.
sample_memory() {
  local pid="$1" out="$2" interval="$3" max="$4"
  if [ -n "$REMOTE_HOST" ]; then
    "${SSH[@]}" "$REMOTE_HOST" "bash -s -- $pid /dev/stdout $interval $max" \
      < "$SCRIPT_DIR/sample-memory.sh" > "$out"
  else
    "$SCRIPT_DIR/sample-memory.sh" "$pid" "$out" "$interval" "$max"
  fi
}

cleanup() {
  [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" 2>/dev/null || true
  stop_server
}
trap cleanup EXIT

# Run one k6 scenario, recording its exit code beside the summary export. Worth recording because
# nothing inside the export says whether it covers a whole run; which codes are publishable is the
# reader's call, and summarize.py's K6_THRESHOLDS_CROSSED is where that lives.
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
  local base="http://${SERVER_HOST}:${port}"
  local -a load_env=(--env VUS="$VUS" --env DURATION="$DURATION" --env RAMP="$RAMP")
  echo "================ $name ($base) ================"

  # Drop every artifact this target is about to produce, before it produces any of them.
  # RESULTS_DIR defaults to the same ./results on every sweep, and each artifact is written only
  # when the step that owns it reaches it: the exit code after k6 returns, a CSV when the sampler
  # starts. Interrupt a sweep -- the normal response to a 10k-VU run going visibly wrong, cf. #111
  # -- and a hand render would otherwise mix sweeps, either across rows (this target refused, the
  # untouched ones publishing) or inside one (a fresh idle CSV against last sweep's loaded CSV,
  # yielding a memory-per-connection figure neither sweep measured). Clearing per target rather
  # than per scenario covers the window before the first k6 call: the JVM launch and up to 90s of
  # health-waiting. Absence fails closed everywhere; a stale record does not.
  rm -f "$RESULTS/${name}"_{low,high,secured}.{summary.json,exit} \
        "$RESULTS/${name}_idle.csv" "$RESULTS/${name}_high_load.csv"

  if [ -n "$REMOTE_HOST" ]; then
    SERVER_PID=$("${SSH[@]}" "$REMOTE_HOST" \
      "nohup java $JAVA_FLAGS $(printf '%q ' "$@") > /tmp/${name}.server.log 2>&1 < /dev/null & echo \$!")
  else
    # shellcheck disable=SC2086
    java $JAVA_FLAGS "$@" > "$RESULTS/${name}.server.log" 2>&1 &
    SERVER_PID=$!
  fi
  echo "  started pid=$SERVER_PID with flags: $JAVA_FLAGS"

  "$SCRIPT_DIR/wait-for-health.sh" "$base/ping" 90

  echo "  sampling idle memory..."
  sample_memory "$SERVER_PID" "$RESULTS/${name}_idle.csv" 1 6

  echo "  scenario 1: low-concurrency..."
  run_scenario "$name" "$base" low low-concurrency.js

  echo "  scenario 2: high-concurrency (VUS=$VUS, $DURATION)..."
  sample_memory "$SERVER_PID" "$RESULTS/${name}_high_load.csv" 2 100000 &
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
  stop_server
  if [ -n "$REMOTE_HOST" ]; then
    "${SSH[@]}" "$REMOTE_HOST" "cat /tmp/${name}.server.log" > "$RESULTS/${name}.server.log" 2>/dev/null || true
  fi
  # wait for the port to be released, then let client-side TIME_WAIT sockets drain before
  # the next target competes for ephemeral ports.
  for _ in $(seq 1 60); do curl -fs "$base/ping" >/dev/null 2>&1 || break; sleep 0.5; done
  echo "  settling ${SETTLE}s..."
  sleep "$SETTLE"
}

echo "================ environment ================"
if [ -n "$REMOTE_HOST" ]; then
  "${SSH[@]}" "$REMOTE_HOST" "mkdir -p /tmp/nls-env && bash -s /tmp/nls-env $REMOTE_REPO" \
    < "$SCRIPT_DIR/collect-env.sh" > /dev/null
  for f in env-server.txt machine-load.txt; do
    "${SSH[@]}" "$REMOTE_HOST" "cat /tmp/nls-env/$f" > "$RESULTS/$f"
  done
else
  "$SCRIPT_DIR/collect-env.sh" "$RESULTS" "$REPO_ROOT"
fi

netty()           { benchmark_target "netty-loom"      18080 -jar "$NETTY_JAR"; }
tomcat_platform() { benchmark_target "tomcat-platform" 18081 -jar "$TOMCAT_JAR" \
  --spring.profiles.active=platform --server.tomcat.max-connections="$TOMCAT_MAXCONN"; }
tomcat_virtual()  { benchmark_target "tomcat-virtual"  18082 -jar "$TOMCAT_JAR" \
  --spring.profiles.active=virtual --server.tomcat.max-connections="$TOMCAT_MAXCONN" \
  --server.tomcat.threads.max="$TOMCAT_MAXCONN"; }

if [ "$ORDER" = forward ]; then
  SEQUENCE=(netty tomcat_platform tomcat_virtual)
else
  SEQUENCE=(tomcat_virtual tomcat_platform netty)
fi
echo "run order ($ORDER): ${SEQUENCE[*]}"
for target in "${SEQUENCE[@]}"; do "$target"; done

echo "================ summarizing ================"
UNAME=$(uname -srm)
python3 "$SCRIPT_DIR/summarize.py" "$RESULTS" "$VUS" "$JAVA_FLAGS" "$UNAME" "$TOMCAT_MAXCONN" \
  "$RESULTS/env-server.txt" > "$RESULTS/SNAPSHOT.md"
echo "wrote $RESULTS/SNAPSHOT.md"
