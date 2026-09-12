#!/usr/bin/env bash
# Exercise scripts/agent/pipeline.sh against shims for stage.sh and gh.
# Usage: scripts/agent/test-pipeline.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PR_URL="https://github.com/o/r/pull/7"
failed=0

# pipeline.sh finds stage.sh next to itself, so each case gets a copy beside a stage.sh shim.
setup() {
  tmp=$(mktemp -d)
  mkdir -p "$tmp/agent" "$tmp/bin"
  cp "$HERE/pipeline.sh" "$tmp/agent/pipeline.sh"
  cat > "$tmp/agent/stage.sh" <<'SHIM'
#!/usr/bin/env bash
echo "stage $*" >> "$SHIM_EVENTS"
if [ "$SHIM_RC" = 0 ]; then echo "$SHIM_URL"; fi
exit "$SHIM_RC"
SHIM
  cat > "$tmp/bin/gh" <<'SHIM'
#!/usr/bin/env bash
echo "gh $*" >> "$SHIM_EVENTS"
SHIM
  chmod +x "$tmp/agent/stage.sh" "$tmp/bin/gh"
  export SHIM_EVENTS="$tmp/events" SHIM_URL="$PR_URL"
}

# run <stage rc>; sets rc, out, err, events
run() {
  rc=0
  out=$(SHIM_RC=$1 PATH="$tmp/bin:$PATH" "$tmp/agent/pipeline.sh" 999 2> "$tmp/stderr") || rc=$?
  err=$(cat "$tmp/stderr")
  events=$(cat "$SHIM_EVENTS" 2>/dev/null || true)
}

check() {
  local name=$1 cond=$2 detail=$3
  if [ "$cond" = 1 ]; then echo "ok $name"; else echo "FAIL $name: $detail"; failed=1; fi
}

# --- pull request ---
setup
run 0
ok=1; why="rc=$rc stderr=$err events=$events"
[ "$rc" = 0 ] && [ "$out" = "$PR_URL" ] && [ "$events" = "stage 999 implement" ] || ok=0
check pull-request "$ok" "$why"
rm -rf "$tmp"

# --- question ---
setup
run 3
ok=1; why="rc=$rc stdout=$out stderr=$err events=$events"
[ "$rc" = 0 ] && [ -z "$out" ] \
  && [ "$events" = $'stage 999 implement\ngh issue edit 999 --remove-label agent/running --add-label agent/needs-input' ] || ok=0
check question "$ok" "$why"
rm -rf "$tmp"

# --- failure and timeout pass through untouched ---
for stage_rc in 1 124; do
  setup
  run "$stage_rc"
  ok=1; why="rc=$rc stdout=$out events=$events"
  [ "$rc" = "$stage_rc" ] && [ -z "$out" ] && [ "$events" = "stage 999 implement" ] || ok=0
  check "stage-exit-$stage_rc" "$ok" "$why"
  rm -rf "$tmp"
done

exit "$failed"
