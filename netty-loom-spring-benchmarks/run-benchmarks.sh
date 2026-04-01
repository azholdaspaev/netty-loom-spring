#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results/$(date +%Y-%m-%d_%H-%M-%S)"

NETTY_PORT=8081
TOMCAT_PORT=8082
HEALTH_TIMEOUT=30
PIDS=()

# Defaults
RUN_NETTY=true
RUN_TOMCAT=true
SCENARIO=""
SKIP_BUILD=false

usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --netty-only       Run benchmarks only against Netty server"
    echo "  --tomcat-only      Run benchmarks only against Tomcat server"
    echo "  --scenario=NAME    Run a specific scenario (constant-load, ramp-up, spike)"
    echo "  --skip-build       Skip Gradle build step"
    echo "  -h, --help         Show this help"
}

cleanup() {
    echo ""
    echo "Cleaning up..."
    for pid in "${PIDS[@]}"; do
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
        fi
    done
}
trap cleanup EXIT INT TERM

# Parse arguments
for arg in "$@"; do
    case "$arg" in
        --netty-only)   RUN_NETTY=true; RUN_TOMCAT=false ;;
        --tomcat-only)  RUN_NETTY=false; RUN_TOMCAT=true ;;
        --scenario=*)   SCENARIO="${arg#*=}" ;;
        --skip-build)   SKIP_BUILD=true ;;
        -h|--help)      usage; exit 0 ;;
        *)              echo "Unknown option: $arg"; usage; exit 1 ;;
    esac
done

# Check k6
if ! command -v k6 &>/dev/null; then
    echo "Error: k6 is not installed."
    echo "Install with: brew install k6"
    exit 1
fi

# Build
if [ "$SKIP_BUILD" = false ]; then
    echo "Building example applications..."
    cd "$PROJECT_ROOT"
    ./gradlew :netty-loom-spring-example-netty:classes :netty-loom-spring-example-tomcat:classes --quiet
fi

mkdir -p "$RESULTS_DIR"

# Determine which scenarios to run
if [ -n "$SCENARIO" ]; then
    SCENARIOS=("$SCENARIO")
else
    SCENARIOS=("constant-load" "ramp-up" "spike")
fi

wait_for_health() {
    local port=$1
    local elapsed=0
    echo "Waiting for server on port $port..."
    while ! curl -sf "http://localhost:$port/api/benchmark/json" >/dev/null 2>&1; do
        if [ "$elapsed" -ge "$HEALTH_TIMEOUT" ]; then
            echo "Error: Server on port $port did not start within ${HEALTH_TIMEOUT}s"
            return 1
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    echo "Server on port $port is ready (${elapsed}s)"
}

run_benchmarks() {
    local server_name=$1
    local port=$2
    local gradle_module=$3

    echo ""
    echo "========================================="
    echo "  Benchmarking: $server_name (port $port)"
    echo "========================================="

    # Start the application
    cd "$PROJECT_ROOT"
    ./gradlew "$gradle_module:run" --quiet < /dev/null &
    local app_pid=$!
    PIDS+=("$app_pid")

    if ! wait_for_health "$port"; then
        kill "$app_pid" 2>/dev/null || true
        return 1
    fi

    # Run each scenario
    for scenario in "${SCENARIOS[@]}"; do
        local script="$SCRIPT_DIR/scripts/${scenario}.js"
        if [ ! -f "$script" ]; then
            echo "Warning: Script not found: $script, skipping"
            continue
        fi

        echo ""
        echo "--- Running scenario: $scenario ---"
        k6 run \
            --summary-export "$RESULTS_DIR/${server_name}-${scenario}-summary.json" \
            -e "BASE_URL=http://localhost:$port" \
            "$script"
    done

    # Stop the application
    kill "$app_pid" 2>/dev/null || true
    wait "$app_pid" 2>/dev/null || true
    # Remove from PIDS array
    PIDS=("${PIDS[@]/$app_pid/}")
}

# Run benchmarks
if [ "$RUN_NETTY" = true ]; then
    run_benchmarks "netty" "$NETTY_PORT" ":netty-loom-spring-example-netty"
fi

if [ "$RUN_TOMCAT" = true ]; then
    run_benchmarks "tomcat" "$TOMCAT_PORT" ":netty-loom-spring-example-tomcat"
fi

echo ""
echo "========================================="
echo "  Benchmark complete!"
echo "  Results: $RESULTS_DIR"
echo "========================================="
ls -la "$RESULTS_DIR"
