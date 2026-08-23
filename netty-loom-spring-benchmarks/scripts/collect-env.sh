#!/usr/bin/env bash
# Capture the machine this sweep ran on, as Markdown bullets summarize.py inlines verbatim.
#
# Exists because every field below was previously typed into SNAPSHOT.md by hand -- and
# machine-load.txt, which both docs/benchmarks/*/COMPARISON.md cite, was written by nothing at all.
#
# Writes into <results_dir>:
#   env-server.txt    provenance block, passed to summarize.py
#   machine-load.txt  load average and the top CPU consumers, for "was the box quiet?"
#
# Usage:  ./collect-env.sh <results_dir> [repo_root]
#
# repo_root is explicit so the script can be piped to a remote shell (`ssh host bash -s`), where
# BASH_SOURCE carries no path to derive it from.
set -euo pipefail

RESULTS="${1:?usage: collect-env.sh <results_dir> [repo_root]}"
REPO_ROOT="${2:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
mkdir -p "$RESULTS"


case "$(uname -s)" in
  Linux)
    cpu=$(awk -F': ' '/^model name/{print $2; exit}' /proc/cpuinfo)
    phys=$(lscpu | awk -F': +' '/^Core\(s\) per socket/{c=$2} /^Socket\(s\)/{s=$2} END{print c*s}')
    logical=$(nproc)
    mem=$(free -h | awk '/^Mem:/{print $2}')
    os=$(. /etc/os-release && echo "$PRETTY_NAME")
    kernel=$(uname -r)
    gov=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo unknown)
    turbo=$([ "$(cat /sys/devices/system/cpu/intel_pstate/no_turbo 2>/dev/null || echo 1)" = 0 ] && echo on || echo off)
    smt=$(cat /sys/devices/system/cpu/smt/control 2>/dev/null || echo n/a)
    ports=$(sysctl -n net.ipv4.ip_local_port_range 2>/dev/null | tr '\t' ' ')
    twreuse=$(sysctl -n net.ipv4.tcp_tw_reuse 2>/dev/null || echo n/a)
    nic=$(ip -o -4 route show to default 2>/dev/null | awk '{print $5; exit}')
    speed=$(sudo -n ethtool "$nic" 2>/dev/null | awk -F': ' '/Speed:/{print $2; exit}' || true)
    limits="nofile $(ulimit -n), ip_local_port_range \`${ports}\`, tcp_tw_reuse ${twreuse}"
    net="${nic:-?} ${speed:-unknown speed}"
    ;;
  Darwin)
    cpu=$(sysctl -n machdep.cpu.brand_string)
    phys=$(sysctl -n hw.physicalcpu)
    logical=$(sysctl -n hw.logicalcpu)
    mem="$(( $(sysctl -n hw.memsize) / 1024 / 1024 / 1024 ))Gi"
    os="macOS $(sw_vers -productVersion)"
    kernel=$(uname -r)
    gov=n/a; turbo=n/a; smt=n/a
    ports=$(sysctl -n net.inet.ip.portrange.first net.inet.ip.portrange.last 2>/dev/null | tr '\n' ' ')
    limits="nofile $(ulimit -n), ephemeral ports \`${ports% }\`"
    net="loopback"
    ;;
  *) echo "collect-env.sh: unsupported $(uname -s)" >&2; exit 1 ;;
esac

# Read each tool's output whole. `head -1` and `sed -n 1p` close the pipe early, and the SIGPIPE
# that sends upstream fails the script under `set -o pipefail`.
java_v=$(java -version 2>&1 || echo unknown)
jdk=$(printf '%s\n' "$java_v" | awk 'NR==1{gsub(/"/,""); sub(/ version/,""); print}')
vm=$(printf '%s\n' "$java_v" | awk 'NR==2{sub(/.*\(build /,""); sub(/\)$/,""); print}')
k6v=$(k6 version 2>/dev/null | awk 'NR==1' || echo "not found")
commit=$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)
dirty=""
[ -n "$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null)" ] && dirty=" (dirty)"

cat > "$RESULTS/env-server.txt" <<EOF
- **Machine:** $(hostname -s) — ${cpu}, ${mem} RAM
- **CPU:** ${phys} physical cores / ${logical} logical (SMT ${smt}), governor \`${gov}\`, turbo ${turbo}
- **OS:** ${os}, kernel ${kernel} ($(uname -m))
- **JDK:** ${jdk} (${vm})
- **k6:** ${k6v}
- **Commit:** \`${commit}\`${dirty}
- **Limits:** ${limits}
- **Network:** ${net}
EOF

{
  date -u '+captured %Y-%m-%dT%H:%M:%SZ'
  uptime
  echo
  ps aux | sort -rnk3 | awk 'NR<=8'
} > "$RESULTS/machine-load.txt"

echo "wrote $RESULTS/env-server.txt and $RESULTS/machine-load.txt"
