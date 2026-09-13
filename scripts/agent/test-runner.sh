#!/usr/bin/env bash
# Exercise scripts/agent/runner.sh against shims for gh, pipeline.sh, stage.sh, requeue.sh and gradlew
# around a scratch clone. Usage: scripts/agent/test-runner.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PR_URL="https://github.com/o/r/pull/7"
failed=0

export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid

# runner.sh takes the main clone and its siblings from its own path, so each case gets a copy of
# it inside $tmp/main/scripts/agent beside shims for the scripts it chains, a gradlew shim
# committed on main so every new worktree carries it, gh first on PATH and a fresh HOME. The gh
# shim applies the runner's own --jq expression to a state file, so the expressions are exercised.
setup() {
  tmp=$(mktemp -d)
  mkdir -p "$tmp/bin" "$tmp/home/.netty-loom-agent" "$tmp/state"
  git init -q --bare -b main "$tmp/origin"
  git clone -q "$tmp/origin" "$tmp/main" 2>/dev/null
  cat > "$tmp/main/gradlew" <<'SHIM'
#!/usr/bin/env bash
echo "gradlew $* in $(pwd -P)" >> "$SHIM_EVENTS"
SHIM
  chmod +x "$tmp/main/gradlew"
  echo root > "$tmp/main/src.txt"
  git -C "$tmp/main" add gradlew src.txt
  git -C "$tmp/main" commit -q -m "root"
  git -C "$tmp/main" push -q origin HEAD:main
  mkdir -p "$tmp/main/scripts/agent"
  cp "$HERE/runner.sh" "$tmp/main/scripts/agent/runner.sh"
  cat > "$tmp/main/scripts/agent/pipeline.sh" <<'SHIM'
#!/usr/bin/env bash
echo "pipeline $* in $(pwd -P)" >> "$SHIM_EVENTS"
if [ -n "${SHIM_PIPELINE_RC:-}" ]; then
  for i in $(seq 1 40); do echo "pipeline stderr line $i" >&2; done
  exit "$SHIM_PIPELINE_RC"
fi
echo "https://github.com/o/r/pull/7"
SHIM
  cat > "$tmp/main/scripts/agent/stage.sh" <<'SHIM'
#!/usr/bin/env bash
echo "stage $* in $(pwd -P)" >> "$SHIM_EVENTS"
[ "$2" != "${SHIM_STAGE_COMMIT:-}" ] || git commit -q --allow-empty -m "NL-$1 $2"
if [ -n "${SHIM_STAGE_RC:-}" ] && [ "$2" = "${SHIM_FAIL_STAGE:-$2}" ]; then
  for i in $(seq 1 40); do echo "stage stderr line $i" >&2; done
  exit "$SHIM_STAGE_RC"
fi
SHIM
  cat > "$tmp/main/scripts/agent/requeue.sh" <<'SHIM'
#!/usr/bin/env bash
echo "requeue" >> "$SHIM_EVENTS"
SHIM
  : > "$tmp/events"
  echo '[]' > "$tmp/state/queued.json"
  echo '[]' > "$tmp/state/running.json"
  echo '[]' > "$tmp/state/closed.json"
  echo '[]' > "$tmp/state/fix-prs.json"
  : > "$tmp/state/merged"
  cat > "$tmp/bin/gh" <<'SHIM'
#!/usr/bin/env bash
echo "gh $*" >> "$SHIM_EVENTS"
jqarg() { local prev=; for a in "$@"; do [ "$prev" = --jq ] && { printf '%s' "$a"; return; }; prev=$a; done; }
case "$*" in
  "issue list --label agent/queued --state open --json number --jq "*) jq -r "$(jqarg "$@")" "$SHIM_STATE/queued.json" ;;
  "issue list --label agent/running --state open --json number,labels --jq "*) jq -r "$(jqarg "$@")" "$SHIM_STATE/running.json" ;;
  "issue list --state closed --search label:agent/"*" --json number,labels --jq "*) jq -r "$(jqarg "$@")" "$SHIM_STATE/closed.json" ;;
  "issue view "*" --json "*" --jq "*) jq -r "$(jqarg "$@")" "$SHIM_STATE/issue-$3.json" ;;
  "issue edit "*) echo "https://github.com/o/r/issues/$3" ;;
  "issue comment "*" --body-file -") cat > "$SHIM_STATE/issue-comment-$3"; echo "https://github.com/o/r/issues/$3#issuecomment-1" ;;
  "pr list --label agent/fix --state open --json url,headRefName --jq "*) jq -r "$(jqarg "$@")" "$SHIM_STATE/fix-prs.json" ;;
  "pr list --head "*" --state merged --json number --jq "*)
    if grep -qx "$4" "$SHIM_STATE/merged"; then echo '[{"number": 1}]'; else echo '[]'; fi | jq -r "$(jqarg "$@")" ;;
  "pr edit "*) echo "$3" ;;
  "pr comment "*" --body-file -") cat > "$SHIM_STATE/pr-comment"; echo "$3#issuecomment-1" ;;
esac
SHIM
  chmod +x "$tmp/main/scripts/agent/"*.sh "$tmp/bin/gh"
  export SHIM_EVENTS="$tmp/events" SHIM_STATE="$tmp/state"
}

# issue <n> <title> [<labels csv>]: known to gh issue view; queue <n> <title>: also listed as agent/queued;
# running <n> <title> [<labels csv>]: also listed as agent/running; closed <n> <labels csv>: a closed issue in the label search
issue() {
  jq -n --arg title "$2" --arg labels "${3:-}" \
    '{title: $title, labels: ($labels | split(",") | map(select(. != "")) | map({name: .}))}' \
    > "$tmp/state/issue-$1.json"
}
listed() {
  jq --argjson n "$1" --arg labels "${3:-}" \
    '. + [{number: $n, labels: ($labels | split(",") | map(select(. != "")) | map({name: .}))}]' \
    "$tmp/state/$2.json" > "$tmp/state/$2.new"
  mv "$tmp/state/$2.new" "$tmp/state/$2.json"
}
queue() { issue "$1" "$2" "agent/queued"; listed "$1" queued; }
running() { issue "$1" "$2" "${3:-agent/running}"; listed "$1" running "${3:-agent/running}"; }
closed() { listed "$1" closed "$2"; }

# fixpr <url> <branch>: an open pull request labelled agent/fix; merged <branch>: its pull request is merged;
# worktree <branch>: what a previous tick left in netty-loom-wt
fixpr() {
  jq --arg url "$1" --arg branch "$2" '. + [{url: $url, headRefName: $branch}]' "$tmp/state/fix-prs.json" > "$tmp/state/fix.new"
  mv "$tmp/state/fix.new" "$tmp/state/fix-prs.json"
}
merged() { echo "$1" >> "$tmp/state/merged"; }
worktree() { git -C "$tmp/main" worktree add -q "$tmp/netty-loom-wt/$1" -b "$1" origin/main; }

# runs the tick from outside the clone; sets rc, out, err, actions (everything but reads, in order)
run() {
  rc=0
  out=$(cd "$tmp" && HOME=$tmp/home PATH="$tmp/bin:$PATH" "$tmp/main/scripts/agent/runner.sh" 2> "$tmp/stderr") || rc=$?
  err=$(cat "$tmp/stderr")
  actions=$(grep -E '^(gh (issue|pr) (edit|comment)|gradlew|pipeline|stage|requeue)' "$SHIM_EVENTS" 2>/dev/null | tr '\n' '|' || true)
}

check() {
  local name=$1 cond=$2 detail=$3
  if [ "$cond" = 1 ]; then echo "ok $name"; else echo "FAIL $name: $detail"; failed=1; fi
}

contains() { case "$1" in *"$2"*) return 0 ;; *) return 1 ;; esac; }

WT7="netty-loom-wt/NL-7-fix-the-thing"
PICK7="gh issue edit 7 --remove-label agent/queued --add-label agent/running|"

# --- a tick while another holds the lock exits at once and touches nothing ---
setup
( exec 9> "$tmp/home/.netty-loom-agent/runner.lock"; flock -n 9; sleep 5 ) &
holder=$!
sleep 0.2
run
kill "$holder" 2>/dev/null; wait "$holder" 2>/dev/null || true
ok=1; why="rc=$rc stdout=$out stderr=$err events=$(cat "$SHIM_EVENTS" 2>/dev/null | tr '\n' '|')"
[ "$rc" = 0 ] && [ -z "$out" ] && [ ! -s "$SHIM_EVENTS" ] || ok=0
check locked "$ok" "$why"
rm -rf "$tmp"

# --- nothing labelled: requeue runs, no worktree, no edit ---
setup
run
ok=1; why="rc=$rc stdout=$out stderr=$err actions=$actions"
[ "$rc" = 0 ] && [ "$actions" = "requeue|" ] && [ ! -d "$tmp/netty-loom-wt" ] || ok=0
check idle "$ok" "$why"
rm -rf "$tmp"

# --- one queued issue: labels swap, worktree from origin/main, sources, pipeline, label off ---
setup
queue 7 "Fix the Thing: quickly!"
run
ok=1; why="rc=$rc stdout=$out stderr=$err actions=$actions"
wt=$(cd "$tmp/$WT7" 2>/dev/null && pwd -P || echo missing)
[ "$rc" = 0 ] || ok=0
[ "$actions" = "requeue|${PICK7}gradlew dependencySources in $wt|pipeline 7 in $wt|gh issue edit 7 --remove-label agent/running|" ] || ok=0
[ "$(git -C "$tmp/$WT7" branch --show-current 2>/dev/null)" = NL-7-fix-the-thing ] || { ok=0; why="$why branch=$(git -C "$tmp/$WT7" branch --show-current 2>&1 || true)"; }
[ "$(git -C "$tmp/$WT7" rev-parse HEAD 2>/dev/null)" = "$(git -C "$tmp/main" rev-parse origin/main)" ] || { ok=0; why="$why HEAD is not origin/main"; }
check queued "$ok" "$why"
rm -rf "$tmp"

# --- two queued: the lowest number goes first, and only one per tick ---
setup
queue 9 "Later"
queue 7 "Fix the Thing: quickly!"
run
ok=1; why="rc=$rc stderr=$err actions=$actions"
[ "$rc" = 0 ] && contains "$actions" "pipeline 7 in" && ! contains "$actions" "pipeline 9" \
  && [ ! -d "$tmp/netty-loom-wt/NL-9-later" ] || ok=0
check oldest-first "$ok" "$why"
rm -rf "$tmp"

# --- the worktree already exists: reused as it is, HEAD untouched ---
setup
queue 7 "Fix the Thing: quickly!"
worktree NL-7-fix-the-thing
echo work > "$tmp/$WT7/work.txt"
git -C "$tmp/$WT7" add work.txt
git -C "$tmp/$WT7" commit -q -m "NL-7 Work"
head=$(git -C "$tmp/$WT7" rev-parse HEAD)
run
ok=1; why="rc=$rc stderr=$err actions=$actions"
[ "$rc" = 0 ] && contains "$actions" "pipeline 7 in" \
  && [ "$(git -C "$tmp/$WT7" rev-parse HEAD)" = "$head" ] || ok=0
check retry "$ok" "$why"
rm -rf "$tmp"

# --- the pipeline fails: agent/failed, the last 30 stderr lines and the log path on the issue ---
setup
queue 7 "Fix the Thing: quickly!"
export SHIM_PIPELINE_RC=1
run
unset SHIM_PIPELINE_RC
comment=$(cat "$tmp/state/issue-comment-7" 2>/dev/null || true)
log="$tmp/home/.netty-loom-agent/logs/NL-7/runner.log"
ok=1; why="rc=$rc stderr=$err actions=$actions comment=$comment"
[ "$rc" = 0 ] || ok=0
contains "$actions" "pipeline 7 in" || ok=0
contains "$actions" "gh issue edit 7 --remove-label agent/running --add-label agent/failed|gh issue comment 7 --body-file -|" || ok=0
contains "$comment" "pipeline stderr line 11" && contains "$comment" "pipeline stderr line 40" \
  && ! contains "$comment" "pipeline stderr line 10" || ok=0
contains "$comment" "$log" || ok=0
[ "$(grep -c 'pipeline stderr line' "$log" 2>/dev/null)" = 40 ] || { ok=0; why="$why log=$(wc -l < "$log" 2>&1 || true)"; }
check failure "$ok" "$why"
rm -rf "$tmp"

# --- agent/fix on a pull request: a fix stage then a review stage in its worktree, no test stage, then the label comes off ---
setup
fixpr "$PR_URL" NL-7-fix-the-thing
worktree NL-7-fix-the-thing
run
wt=$(cd "$tmp/$WT7" && pwd -P)
ok=1; why="rc=$rc stderr=$err actions=$actions"
[ "$rc" = 0 ] && [ "$actions" = "requeue|stage 7 fix $PR_URL in $wt|stage 7 review $PR_URL in $wt|gh pr edit $PR_URL --remove-label agent/fix|" ] || ok=0
check fix "$ok" "$why"
rm -rf "$tmp"

# --- the fix stage fails: agent/failed on the pull request, the stderr tail and the log path as a comment ---
setup
fixpr "$PR_URL" NL-7-fix-the-thing
worktree NL-7-fix-the-thing
export SHIM_STAGE_RC=1
run
unset SHIM_STAGE_RC
comment=$(cat "$tmp/state/pr-comment" 2>/dev/null || true)
log="$tmp/home/.netty-loom-agent/logs/NL-7/runner.log"
ok=1; why="rc=$rc stderr=$err actions=$actions comment=$comment"
[ "$rc" = 0 ] || ok=0
contains "$actions" "stage 7 fix $PR_URL in " && ! contains "$actions" "stage 7 review" || ok=0
contains "$actions" "gh pr edit $PR_URL --remove-label agent/fix --add-label agent/failed|gh pr comment $PR_URL --body-file -|" || ok=0
contains "$comment" "fix stage failed (exit 1)" || ok=0
contains "$comment" "stage stderr line 11" && contains "$comment" "stage stderr line 40" \
  && ! contains "$comment" "stage stderr line 10" && contains "$comment" "$log" || ok=0
check fix-failure "$ok" "$why"
rm -rf "$tmp"

# --- the review stage fails after a successful fix: agent/failed, the fix commit stays ---
setup
fixpr "$PR_URL" NL-7-fix-the-thing
worktree NL-7-fix-the-thing
export SHIM_STAGE_COMMIT=fix SHIM_FAIL_STAGE=review SHIM_STAGE_RC=1
run
unset SHIM_STAGE_COMMIT SHIM_FAIL_STAGE SHIM_STAGE_RC
comment=$(cat "$tmp/state/pr-comment" 2>/dev/null || true)
log="$tmp/home/.netty-loom-agent/logs/NL-7/runner.log"
wt=$(cd "$tmp/$WT7" && pwd -P)
ok=1; why="rc=$rc stderr=$err actions=$actions comment=$comment"
[ "$rc" = 0 ] || ok=0
contains "$actions" "stage 7 fix $PR_URL in $wt|stage 7 review $PR_URL in $wt|gh pr edit $PR_URL --remove-label agent/fix --add-label agent/failed|gh pr comment $PR_URL --body-file -|" || ok=0
contains "$comment" "review stage failed (exit 1)" && contains "$comment" "stage stderr line 40" && contains "$comment" "$log" || ok=0
[ "$(git -C "$tmp/$WT7" log -1 --format=%s)" = "NL-7 fix" ] || { ok=0; why="$why head=$(git -C "$tmp/$WT7" log -1 --format=%s)"; }
check fix-review-failure "$ok" "$why"
rm -rf "$tmp"

# --- agent/fix on a pull request whose branch has no worktree yet: one is added from origin ---
setup
fixpr "$PR_URL" NL-7-fix-the-thing
worktree NL-7-fix-the-thing
echo work > "$tmp/$WT7/work.txt"
git -C "$tmp/$WT7" add work.txt
git -C "$tmp/$WT7" commit -q -m "NL-7 Work"
git -C "$tmp/$WT7" push -q origin NL-7-fix-the-thing
head=$(git -C "$tmp/$WT7" rev-parse HEAD)
git -C "$tmp/main" worktree remove "$tmp/$WT7"
git -C "$tmp/main" branch -q -D NL-7-fix-the-thing
run
ok=1; why="rc=$rc stderr=$err actions=$actions"
[ "$rc" = 0 ] && contains "$actions" "stage 7 fix $PR_URL in " || ok=0
[ "$(git -C "$tmp/$WT7" branch --show-current 2>/dev/null || true)" = NL-7-fix-the-thing ] || { ok=0; why="$why worktree missing or on another branch"; }
[ "$(git -C "$tmp/$WT7" rev-parse HEAD 2>/dev/null || true)" = "$head" ] || { ok=0; why="$why HEAD is not the pushed branch"; }
check fix-without-worktree "$ok" "$why"
rm -rf "$tmp"

# --- a merged pull request: worktree and branch go, the issue keeps only its non-agent labels ---
setup
worktree NL-7-fix-the-thing
merged NL-7-fix-the-thing
issue 7 "Fix the Thing: quickly!" "enhancement,agent/running,agent/pr-ready"
run
ok=1; why="rc=$rc stderr=$err actions=$actions"
[ "$rc" = 0 ] && [ "$actions" = "gh issue edit 7 --remove-label agent/running,agent/pr-ready|requeue|" ] || ok=0
[ ! -d "$tmp/$WT7" ] || { ok=0; why="$why worktree still there"; }
[ -z "$(git -C "$tmp/main" branch --list NL-7-fix-the-thing)" ] || { ok=0; why="$why branch still there"; }
check merged "$ok" "$why"
rm -rf "$tmp"

# --- an open agent/running issue at tick start is an orphan: agent/failed, one comment with the log path and the retry line, then the tick goes on ---
setup
running 7 "Fix the Thing: quickly!"
queue 8 "New work"
run
comment=$(cat "$tmp/state/issue-comment-7" 2>/dev/null || true)
log="$tmp/home/.netty-loom-agent/logs/NL-7/runner.log"
wt8=$(cd "$tmp/netty-loom-wt/NL-8-new-work" 2>/dev/null && pwd -P || echo missing)
ok=1; why="rc=$rc stderr=$err actions=$actions comment=$comment"
[ "$rc" = 0 ] && [ -z "$err" ] || ok=0
[ "$actions" = "gh issue edit 7 --remove-label agent/running --add-label agent/failed|gh issue comment 7 --body-file -|requeue|gh issue edit 8 --remove-label agent/queued --add-label agent/running|gradlew dependencySources in $wt8|pipeline 8 in $wt8|gh issue edit 8 --remove-label agent/running|" ] || ok=0
contains "$comment" "$log" && contains "$comment" 'Replace `agent/failed` with `agent/queued`' || ok=0
[ ! -d "$tmp/$WT7" ] || { ok=0; why="$why a worktree was added for the orphan"; }
check orphan "$ok" "$why"
rm -rf "$tmp"

# --- agent/running beside agent/pr-ready: the pipeline finished and only the runner died, so the label comes off and no comment is posted ---
setup
running 7 "Fix the Thing: quickly!" "agent/running,agent/pr-ready"
run
ok=1; why="rc=$rc stderr=$err actions=$actions"
[ "$rc" = 0 ] && [ "$actions" = "gh issue edit 7 --remove-label agent/running|requeue|" ] || ok=0
check orphan-pr-ready "$ok" "$why"
rm -rf "$tmp"

# --- a closed issue still carrying agent/* labels loses them, nothing else ---
setup
closed 5 "enhancement,agent/pr-ready"
run
ok=1; why="rc=$rc stderr=$err actions=$actions"
[ "$rc" = 0 ] && [ "$actions" = "gh issue edit 5 --remove-label agent/pr-ready|requeue|" ] || ok=0
check stale-labels "$ok" "$why"
rm -rf "$tmp"

# --- everything at once: sweep, clean up, requeue, fix, then one queued issue; the orphan's comment tails its log ---
setup
running 6 "Dead"
mkdir -p "$tmp/home/.netty-loom-agent/logs/NL-6"
for i in $(seq 1 40); do echo "old stderr line $i"; done > "$tmp/home/.netty-loom-agent/logs/NL-6/runner.log"
closed 4 "agent/queued,agent/failed"
worktree NL-5-done
merged NL-5-done
issue 5 "Done" "agent/pr-ready"
fixpr "$PR_URL" NL-7-fix-the-thing
worktree NL-7-fix-the-thing
queue 8 "New work"
run
comment=$(cat "$tmp/state/issue-comment-6" 2>/dev/null || true)
wt7=$(cd "$tmp/$WT7" && pwd -P); wt8=$(cd "$tmp/netty-loom-wt/NL-8-new-work" 2>/dev/null && pwd -P || echo missing)
ok=1; why="rc=$rc stderr=$err actions=$actions comment=$comment"
[ "$rc" = 0 ] && [ "$actions" = "gh issue edit 6 --remove-label agent/running --add-label agent/failed|gh issue comment 6 --body-file -|gh issue edit 4 --remove-label agent/queued,agent/failed|gh issue edit 5 --remove-label agent/pr-ready|requeue|stage 7 fix $PR_URL in $wt7|stage 7 review $PR_URL in $wt7|gh pr edit $PR_URL --remove-label agent/fix|gh issue edit 8 --remove-label agent/queued --add-label agent/running|gradlew dependencySources in $wt8|pipeline 8 in $wt8|gh issue edit 8 --remove-label agent/running|" ] || ok=0
contains "$comment" "old stderr line 11" && contains "$comment" "old stderr line 40" && ! contains "$comment" "old stderr line 10" || ok=0
check order "$ok" "$why"
rm -rf "$tmp"

exit "$failed"
