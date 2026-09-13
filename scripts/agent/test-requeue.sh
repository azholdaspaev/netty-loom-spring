#!/usr/bin/env bash
# Exercise scripts/agent/requeue.sh against a gh shim; the runner's login is the repository owner, as live.
# Usage: scripts/agent/test-requeue.sh
set -euo pipefail

REQUEUE_SH="$(cd "$(dirname "$0")" && pwd)/requeue.sh"
failed=0

setup() {
  tmp=$(mktemp -d)
  mkdir -p "$tmp/bin"
  : > "$tmp/issues"
  : > "$tmp/pr-ready"
  cat > "$tmp/bin/gh" <<'SHIM'
#!/usr/bin/env bash
echo "gh $*" >> "$SHIM_EVENTS"
case "$*" in
  "repo view --json nameWithOwner --jq .nameWithOwner") echo o/r ;;
  "api user --jq .login") echo o ;;
  "issue list --label agent/needs-input --state open --json number --jq .[].number") cat "$SHIM_DIR/issues" ;;
  "issue list --label agent/pr-ready --state open --json number --jq .[].number") cat "$SHIM_DIR/pr-ready" ;;
  "api --paginate repos/o/r/issues/"*"/comments?per_page=100") n=${3#repos/o/r/issues/}; cat "$SHIM_DIR/comments-${n%%/*}.json" ;;
esac
SHIM
  chmod +x "$tmp/bin/gh"
  export SHIM_EVENTS="$tmp/events" SHIM_DIR="$tmp"
}

# thread <issue> <login:q|c>...  -- q posts the marker, c a plain comment; order is chronological;
# the issue is listed as agent/needs-input, or as agent/pr-ready under LIST=pr-ready
thread() {
  local n=$1 i=0 list='[]'; shift
  echo "$n" >> "$tmp/${LIST:-issues}"
  for spec in "$@"; do
    i=$((i + 1))
    local body="comment $i"
    [ "${spec#*:}" = q ] && body=$'<!-- agent:question -->\nWhich?'
    list=$(jq --arg login "${spec%:*}" --arg body "$body" --arg at "2026-09-12T12:00:0${i}Z" \
      '. + [{user: {login: $login}, body: $body, created_at: $at}]' <<< "$list")
  done
  echo "$list" > "$tmp/comments-$n.json"
}

run() {
  rc=0
  out=$(PATH="$tmp/bin:$PATH" "$REQUEUE_SH" 2> "$tmp/stderr") || rc=$?
  err=$(cat "$tmp/stderr")
  edits=$(grep '^gh issue edit' "$SHIM_EVENTS" 2>/dev/null || true)
}

check() {
  local name=$1 cond=$2 detail=$3
  if [ "$cond" = 1 ]; then echo "ok $name"; else echo "FAIL $name: $detail"; failed=1; fi
}

# case <name> <expected edits> <thread args...>
case_() {
  local name=$1 expected=$2; shift 2
  setup
  thread "$@"
  run
  ok=1; why="rc=$rc stdout=$out stderr=$err edits=$edits"
  [ "$rc" = 0 ] && [ -z "$out" ] && [ "$edits" = "$expected" ] || ok=0
  check "$name" "$ok" "$why"
  rm -rf "$tmp"
}

requeue_5="gh issue edit 5 --remove-label agent/needs-input --add-label agent/queued"

case_ answered        "$requeue_5" 5 o:q o:c
case_ pending         ""           5 o:q
case_ third-party     ""           5 o:q x:c
case_ no-marker       ""           5 o:c
case_ second-question ""           5 o:q o:c o:q
case_ owner-then-third-party ""    5 o:q o:c x:c

# An agent/pr-ready issue whose newest comment is the marker: a stage asked after the hand-over
# and could not read its question back, so the label is put on here; anything after the marker
# may be the runner's own hand-over comment, so only the newest comment counts.
label_5="gh issue edit 5 --add-label agent/needs-input"

LIST=pr-ready case_ lost-question          "$label_5" 5 o:q
LIST=pr-ready case_ lost-question-followed ""         5 o:q o:c
LIST=pr-ready case_ pr-ready-no-comments   ""         5
LIST=pr-ready case_ pr-ready-third-party   ""         5 x:q

# --- two issues: one answered, one pending; the no-op last iteration must not fail the script ---
setup
thread 5 o:q o:c
thread 6 o:q
run
ok=1; why="rc=$rc stdout=$out stderr=$err edits=$edits"
[ "$rc" = 0 ] && [ -z "$out" ] && [ "$edits" = "$requeue_5" ] || ok=0
check two-issues "$ok" "$why"
rm -rf "$tmp"

# --- nothing queued: no comment lookups at all ---
setup
run
ok=1; why="rc=$rc stderr=$err events=$(tr '\n' '|' < "$SHIM_EVENTS")"
[ "$rc" = 0 ] && ! grep -q "comments" "$SHIM_EVENTS" || ok=0
check nothing-waiting "$ok" "$why"
rm -rf "$tmp"

exit "$failed"
