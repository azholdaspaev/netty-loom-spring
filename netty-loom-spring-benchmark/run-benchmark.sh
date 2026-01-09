#!/bin/bash

# Netty-Loom vs Tomcat Benchmark Suite
#
# This script orchestrates the full benchmark comparison between
# Netty-Loom (with virtual threads) and traditional Tomcat servers.
#
# Prerequisites:
#   - k6 installed (https://k6.io/docs/getting-started/installation/)
#   - Java 21+ installed
#   - Gradle wrapper available
#
# Usage:
#   ./run-benchmark.sh [options]
#
# Options:
#   --quick     Run quick benchmark (shorter durations)
#   --netty     Benchmark Netty only
#   --tomcat    Benchmark Tomcat only
#   --script    Run specific script only (e.g., --script cpu-bound)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results"
# Use glob pattern to find JAR files (handles version suffixes)
NETTY_JAR="$(ls "$PROJECT_ROOT"/netty-loom-spring-example-netty/build/libs/*.jar 2>/dev/null | head -1)"
TOMCAT_JAR="$(ls "$PROJECT_ROOT"/netty-loom-spring-example-tomcat/build/libs/*.jar 2>/dev/null | head -1)"

NETTY_PORT=8081
TOMCAT_PORT=8082
NETTY_PID=""
TOMCAT_PID=""

# Parse arguments
QUICK_MODE=false
NETTY_ONLY=false
TOMCAT_ONLY=false
SPECIFIC_SCRIPT=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --quick)
            QUICK_MODE=true
            shift
            ;;
        --netty)
            NETTY_ONLY=true
            shift
            ;;
        --tomcat)
            TOMCAT_ONLY=true
            shift
            ;;
        --script)
            SPECIFIC_SCRIPT="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."

    if ! command -v k6 &> /dev/null; then
        log_error "k6 is not installed. Please install it from https://k6.io/docs/getting-started/installation/"
        exit 1
    fi

    if ! command -v java &> /dev/null; then
        log_error "Java is not installed. Please install Java 21+."
        exit 1
    fi

    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 21 ]; then
        log_error "Java 21+ is required. Current version: $JAVA_VERSION"
        exit 1
    fi

    log_success "Prerequisites check passed (k6 installed, Java $JAVA_VERSION)"
}

# Build applications
build_apps() {
    log_info "Building example applications..."
    cd "$PROJECT_ROOT"

    if [ "$TOMCAT_ONLY" = false ]; then
        ./gradlew :netty-loom-spring-example-netty:bootJar --quiet
        if [ ! -f "$NETTY_JAR" ]; then
            log_error "Netty JAR not found at $NETTY_JAR"
            exit 1
        fi
        log_success "Built Netty-Loom example"
    fi

    if [ "$NETTY_ONLY" = false ]; then
        ./gradlew :netty-loom-spring-example-tomcat:bootJar --quiet
        if [ ! -f "$TOMCAT_JAR" ]; then
            log_error "Tomcat JAR not found at $TOMCAT_JAR"
            exit 1
        fi
        log_success "Built Tomcat example"
    fi
}

# Start server and wait for it to be ready
start_server() {
    local name=$1
    local jar=$2
    local port=$3

    log_info "Starting $name server on port $port..."

    java -jar "$jar" &
    local pid=$!
    echo $pid

    # Wait for server to be ready
    local max_attempts=30
    local attempt=0
    while [ $attempt -lt $max_attempts ]; do
        if curl -s "http://localhost:$port/hello" > /dev/null 2>&1; then
            log_success "$name server started (PID: $pid)"
            return 0
        fi
        sleep 1
        attempt=$((attempt + 1))
    done

    log_error "$name server failed to start"
    kill $pid 2>/dev/null || true
    return 1
}

# Stop server
stop_server() {
    local name=$1
    local pid=$2

    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        log_info "Stopping $name server (PID: $pid)..."
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
        log_success "$name server stopped"
    fi
}

# Run benchmarks
run_benchmarks() {
    local target_name=$1
    local target_url=$2

    log_info "Running benchmarks against $target_name ($target_url)"
    mkdir -p "$RESULTS_DIR/$target_name"

    local scripts=("cpu-bound" "io-bound" "mixed-workload" "high-concurrency")

    if [ -n "$SPECIFIC_SCRIPT" ]; then
        scripts=("$SPECIFIC_SCRIPT")
    fi

    for script in "${scripts[@]}"; do
        log_info "Running $script benchmark..."

        local script_file="$SCRIPT_DIR/scripts/${script}.js"
        if [ ! -f "$script_file" ]; then
            log_warn "Script not found: $script_file, skipping..."
            continue
        fi

        # Run k6 with output to file
        k6 run "$script_file" \
            --env TARGET="$target_url" \
            --out json="$RESULTS_DIR/$target_name/${script}-results.json" \
            2>&1 | tee "$RESULTS_DIR/$target_name/${script}-output.txt"

        log_success "Completed $script benchmark"
        echo ""
    done
}

# Cleanup on exit
cleanup() {
    log_info "Cleaning up..."
    stop_server "Netty" "$NETTY_PID"
    stop_server "Tomcat" "$TOMCAT_PID"
}

trap cleanup EXIT

# Generate comparison report
generate_report() {
    log_info "Generating comparison report..."

    local report_file="$RESULTS_DIR/benchmark-report.txt"
    {
        echo "=========================================="
        echo "Netty-Loom vs Tomcat Benchmark Report"
        echo "=========================================="
        echo ""
        echo "Generated: $(date)"
        echo ""

        for script in "cpu-bound" "io-bound" "mixed-workload" "high-concurrency"; do
            echo "--- $script ---"

            if [ -f "$RESULTS_DIR/netty/${script}-summary.json" ]; then
                echo "Netty-Loom:"
                cat "$RESULTS_DIR/netty/${script}-summary.json" 2>/dev/null || echo "  No summary available"
            fi

            if [ -f "$RESULTS_DIR/tomcat/${script}-summary.json" ]; then
                echo "Tomcat:"
                cat "$RESULTS_DIR/tomcat/${script}-summary.json" 2>/dev/null || echo "  No summary available"
            fi
            echo ""
        done
    } > "$report_file"

    log_success "Report generated: $report_file"
}

# Main execution
main() {
    echo ""
    echo "======================================"
    echo "  Netty-Loom vs Tomcat Benchmarks"
    echo "======================================"
    echo ""

    check_prerequisites

    # Create results directory
    mkdir -p "$RESULTS_DIR"
    rm -rf "$RESULTS_DIR/netty" "$RESULTS_DIR/tomcat" 2>/dev/null || true

    build_apps

    # Benchmark Netty
    if [ "$TOMCAT_ONLY" = false ]; then
        NETTY_PID=$(start_server "Netty-Loom" "$NETTY_JAR" $NETTY_PORT)
        run_benchmarks "netty" "http://localhost:$NETTY_PORT"
        stop_server "Netty-Loom" "$NETTY_PID"
        NETTY_PID=""
        sleep 2
    fi

    # Benchmark Tomcat
    if [ "$NETTY_ONLY" = false ]; then
        TOMCAT_PID=$(start_server "Tomcat" "$TOMCAT_JAR" $TOMCAT_PORT)
        run_benchmarks "tomcat" "http://localhost:$TOMCAT_PORT"
        stop_server "Tomcat" "$TOMCAT_PID"
        TOMCAT_PID=""
    fi

    generate_report

    echo ""
    log_success "Benchmark suite completed!"
    echo ""
    echo "Results available in: $RESULTS_DIR"
}

main "$@"
