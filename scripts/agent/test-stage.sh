#!/usr/bin/env bash
# Exercise scripts/agent/stage.sh against shims for claude, gh and gradlew in a scratch repository.
# Usage: scripts/agent/test-stage.sh
set -euo pipefail

STAGE_SH="$(cd "$(dirname "$0")" && pwd)/stage.sh"
PR_URL="https://github.com/o/r/pull/7"
failed=0

export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid

# Each case gets a bare origin with main, a clone on NL-999-x, shims first on PATH, and a fresh HOME.
setup() {
  tmp=$(mktemp -d)
  mkdir -p "$tmp/bin" "$tmp/home"
  git init -q --bare -b main "$tmp/origin"
  git clone -q "$tmp/origin" "$tmp/work" 2>/dev/null
  git -C "$tmp/work" commit -q --allow-empty -m "root"
  git -C "$tmp/work" push -q origin HEAD:main
  git -C "$tmp/work" checkout -q -b NL-999-x

  cat > "$tmp/work/gradlew" <<'SHIM'
#!/usr/bin/env bash
echo "gradlew $*" >> "$SHIM_EVENTS"
SHIM
  cat > "$tmp/bin/gh" <<'SHIM'
#!/usr/bin/env bash
echo "gh $*" >> "$SHIM_EVENTS"
case "$*" in
  "pr list --head NL-999-x "*) if [ -n "${SHIM_PR_URL:-}" ]; then echo "$SHIM_PR_URL"; fi ;;
esac
SHIM
  cat > "$tmp/bin/claude" <<'SHIM'
#!/usr/bin/env bash
echo "claude" >> "$SHIM_EVENTS"
: > "$SHIM_ARGV"
prev=
for arg in "$@"; do
  if [ "$prev" = --append-system-prompt ]; then printf '%s' "$arg" > "$SHIM_ARGV.system"; fi
  printf '%s\n' "$arg" >> "$SHIM_ARGV"
  prev=$arg
done
echo "shim stderr line" >&2
result() { printf '{"type":"result","subtype":"%s","is_error":%s,"session_id":"s","total_cost_usd":0.5,"num_turns":3}\n' "$1" "$2"; }
commit() { echo x > "$SHIM_MODE.txt"; git add "$SHIM_MODE.txt"; git commit -q -m "NL-999 Work"; }
case "$SHIM_MODE" in
  success)  commit; result success false ;;
  budget)   commit; result error_max_budget_usd true ;;
  nocommit) result success false ;;
  crash)    commit; echo boom; exit 1 ;;
  hang)     sleep 5 ;;
esac
SHIM
  chmod +x "$tmp/work/gradlew" "$tmp/bin/gh" "$tmp/bin/claude"
  export SHIM_EVENTS="$tmp/events" SHIM_ARGV="$tmp/argv"
}

# run <mode> <pr-url-or-empty> <stage args...>; sets rc, out, err
run() {
  local mode=$1 url=$2; shift 2
  rc=0
  out=$(cd "$tmp/work" && SHIM_MODE=$mode SHIM_PR_URL=$url HOME=$tmp/home PATH="$tmp/bin:$PATH" \
        "$STAGE_SH" "$@" 2> "$tmp/stderr") || rc=$?
  err=$(cat "$tmp/stderr")
}

check() {
  local name=$1 cond=$2 detail=$3
  if [ "$cond" = 1 ]; then echo "ok $name"; else echo "FAIL $name: $detail"; failed=1; fi
}

contains() { case "$1" in *"$2"*) return 0 ;; *) return 1 ;; esac; }

argv_has() { grep -qxF -- "$1" "$SHIM_ARGV" 2>/dev/null; }

# --- success ---
setup
run success "$PR_URL" 999 implement
ok=1; why=""
[ "$rc" = 0 ] || { ok=0; why="rc=$rc stderr=$err"; }
[ "$out" = "$PR_URL" ] || { ok=0; why="stdout=$out"; }
[ "$(cat "$SHIM_EVENTS" 2>/dev/null || true)" = $'gradlew --stop\nclaude\ngh pr list --head NL-999-x --json url --jq .[0].url\ngradlew --stop' ] \
  || { ok=0; why="events=$(tr '\n' '|' 2>/dev/null < "$SHIM_EVENTS" || true)"; }
for flag in --permission-mode acceptEdits --permission-prompts none --max-budget-usd 8 --output-format json \
            "NL-999 implement" "/flow:implement 999"; do
  argv_has "$flag" || { ok=0; why="argv lacks $flag"; }
done
grep -q '/\.claude/agent/settings\.json$' "$SHIM_ARGV" 2>/dev/null || { ok=0; why="argv lacks the agent settings file"; }
prompt_line=$(grep -nxF -- '/flow:implement 999' "$SHIM_ARGV" 2>/dev/null | cut -d: -f1 || true)
allowed_line=$(grep -nxF -- '--allowedTools' "$SHIM_ARGV" 2>/dev/null | cut -d: -f1 || true)
{ [ -n "$prompt_line" ] && [ -n "$allowed_line" ] && [ "$prompt_line" -lt "$allowed_line" ]; } \
  || { ok=0; why="prompt must precede --allowedTools (prompt line $prompt_line, allowedTools line $allowed_line)"; }
system=$(cat "$SHIM_ARGV.system" 2>/dev/null || true)
for needle in "# Unattended run" "git push -u origin NL-999-x" "--draft" "--body-file build/pr-body.md" \
              ".github/PULL_REQUEST_TEMPLATE.md" "NL-999 "; do
  contains "$system" "$needle" || { ok=0; why="system prompt lacks '$needle'"; }
done
log="$tmp/home/.netty-loom-agent/logs/NL-999"
[ "$(jq -r .subtype "$log/implement.json" 2>/dev/null)" = success ] || { ok=0; why="implement.json missing or wrong"; }
grep -q "shim stderr line" "$log/implement.log" 2>/dev/null || { ok=0; why="implement.log lacks claude's stderr"; }
check success "$ok" "$why"
rm -rf "$tmp"

# Each failure case below is the success case minus exactly one thing, so a check the script
# drops turns precisely one of them green for the wrong reason and red here.

# --- budget ---
setup
run budget "$PR_URL" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "ended with error_max_budget_usd" || ok=0
[ "$(jq -r .subtype "$tmp/home/.netty-loom-agent/logs/NL-999/implement.json" 2>/dev/null || true)" = error_max_budget_usd ] || ok=0
check budget "$ok" "$why"
rm -rf "$tmp"

# --- no pull request ---
setup
run success "" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "no open pull request" || ok=0
check no-pr "$ok" "$why"
rm -rf "$tmp"

# --- no commits ---
setup
run nocommit "$PR_URL" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "no commits" || ok=0
check no-commits "$ok" "$why"
rm -rf "$tmp"

# --- crash: no result JSON ---
setup
run crash "$PR_URL" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "no result (claude exited 1)" || ok=0
check crash "$ok" "$why"
rm -rf "$tmp"

# --- timeout ---
setup
export STAGE_TIMEOUT=1
run hang "$PR_URL" 999 implement
unset STAGE_TIMEOUT
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 124 ] && contains "$err" "timed out" || ok=0
[ ! -s "$tmp/home/.netty-loom-agent/logs/NL-999/implement.json" ] || { ok=0; why="implement.json is not empty"; }
check timeout "$ok" "$why"
rm -rf "$tmp"

# --- wrong branch ---
setup
git -C "$tmp/work" checkout -q main
run success "$PR_URL" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "expected NL-999-" || ok=0
[ ! -e "$SHIM_ARGV" ] || { ok=0; why="claude ran on branch main"; }
check wrong-branch "$ok" "$why"
rm -rf "$tmp"

# --- unknown stage ---
setup
run success "$PR_URL" 999 deploy
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "unknown stage" || ok=0
[ ! -e "$SHIM_ARGV" ] || { ok=0; why="claude ran for an unknown stage"; }
check unknown-stage "$ok" "$why"
rm -rf "$tmp"

exit "$failed"
