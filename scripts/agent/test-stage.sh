#!/usr/bin/env bash
# Exercise scripts/agent/stage.sh against shims for claude, gh and gradlew in a scratch repository.
# Usage: scripts/agent/test-stage.sh
set -euo pipefail

STAGE_SH="$(cd "$(dirname "$0")" && pwd)/stage.sh"
PR_URL="https://github.com/o/r/pull/7"
RUN=20260912T120000Z
failed=0

export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid

# Each case gets a bare origin with main, a clone on NL-999-x, shims first on PATH, a fresh HOME and
# the run id $RUN.
setup() {
  tmp=$(mktemp -d)
  mkdir -p "$tmp/bin" "$tmp/home"
  git init -q --bare -b main "$tmp/origin"
  git clone -q "$tmp/origin" "$tmp/work" 2>/dev/null
  cat > "$tmp/work/gradlew" <<'SHIM'
#!/usr/bin/env bash
echo "gradlew $*" >> "$SHIM_EVENTS"
SHIM
  chmod +x "$tmp/work/gradlew"
  echo root > "$tmp/work/src.txt"
  git -C "$tmp/work" add gradlew src.txt
  git -C "$tmp/work" commit -q -m "root"
  git -C "$tmp/work" push -q origin HEAD:main
  git -C "$tmp/work" checkout -q -b NL-999-x
  echo '[]' > "$tmp/comments"
  cat > "$tmp/bin/gh" <<'SHIM'
#!/usr/bin/env bash
echo "gh $*" >> "$SHIM_EVENTS"
if [ -n "${SHIM_GH_FAIL:-}" ] && { [ -z "${SHIM_GH_FAIL_AFTER:-}" ] || grep -qx claude "$SHIM_EVENTS"; }; then
  case "$*" in "$SHIM_GH_FAIL"*) echo "gh: dial tcp: no route to host" >&2; exit 1 ;; esac
fi
case "$*" in
  "repo view --json nameWithOwner --jq .nameWithOwner") echo o/r ;;
  "api user --jq .login") echo runner ;;
  "api --paginate repos/o/r/issues/999/comments?per_page=100") cat "$SHIM_COMMENTS" ;;
  "issue comment 999 --body-file -")
    jq --arg body "$(cat)" '. + [{user: {login: "runner"}, body: $body, created_at: "2026-09-12T12:00:01Z",
      html_url: "https://github.com/o/r/issues/999#issuecomment-2"}]' "$SHIM_COMMENTS" > "$SHIM_COMMENTS.new"
    mv "$SHIM_COMMENTS.new" "$SHIM_COMMENTS" ;;
  "pr list --head NL-999-x "*) if [ -n "${SHIM_PR_URL:-}" ]; then echo "$SHIM_PR_URL"; fi ;;
  "pr view "*" --json headRefOid "*) echo "${SHIM_HEAD:-$(git rev-parse HEAD)}" ;;
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
result() { printf '{"type":"result","subtype":"%s","is_error":%s,"terminal_reason":"completed","session_id":"s","total_cost_usd":0.5,"num_turns":3}\n' "$1" "$2"; }
commit() { echo x > "$SHIM_MODE.txt"; git add "$SHIM_MODE.txt"; git commit -q -m "NL-999 Work"; }
ask() { printf '<!-- agent:question -->\nWhich one?\n' | gh issue comment 999 --body-file -; }
case "$SHIM_MODE" in
  success)  commit; result success false ;;
  budget)   commit; result error_max_budget_usd true ;;
  nocommit) result success false ;;
  question) ask; result success false ;;
  question-crash) ask; echo boom; exit 1 ;;
  dirty)    echo y >> src.txt; result success false ;;
  crash)    commit; echo boom; exit 1 ;;
  api-error) result success true | jq -c '. + {terminal_reason: "api_error", result: "API Error: 403 blocked"}'; exit 1 ;;
  is-error) commit; result success true ;;
  dropped)  commit; result error_during_execution true; exit 1 ;;
  exit-1)   commit; result success false | jq -c '. + {result: "Done.\n```\nsecond line\n```"}'; exit 1 ;;
  hang)     sleep 5 ;;
esac
SHIM
  chmod +x "$tmp/bin/gh" "$tmp/bin/claude"
  export SHIM_EVENTS="$tmp/events" SHIM_ARGV="$tmp/argv" SHIM_COMMENTS="$tmp/comments" RUN_ID="$RUN"
}

TS='[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z'
RUN_TS='[0-9]{8}T[0-9]{6}Z'

# run <mode> <pr-url-or-empty> <stage args...>; sets rc, out, err and said (stderr with the stage's
# own timestamped prefix stripped, so a line without it stands out)
run() {
  local mode=$1 url=$2; shift 2
  rc=0
  out=$(cd "$tmp/work" && SHIM_MODE=$mode SHIM_PR_URL=$url HOME=$tmp/home PATH="$tmp/bin:$PATH" \
        "$STAGE_SH" "$@" 2> "$tmp/stderr") || rc=$?
  err=$(cat "$tmp/stderr")
  said=$(sed -E "s/^$TS stage\.sh: //" "$tmp/stderr" | tr '\n' '|')
}

check() {
  local name=$1 cond=$2 detail=$3
  if [ "$cond" = 1 ]; then echo "ok $name"; else echo "FAIL $name: $detail"; failed=1; fi
}

contains() { case "$1" in *"$2"*) return 0 ;; *) return 1 ;; esac; }

argv_has() { grep -qxF -- "$1" "$SHIM_ARGV" 2>/dev/null; }

argv_after() { grep -A1 -xF -- "$1" "$SHIM_ARGV" 2>/dev/null | tail -n 1 || true; }

# --- success ---
setup
run success "$PR_URL" 999 implement
ok=1; why=""
[ "$rc" = 0 ] || { ok=0; why="rc=$rc stderr=$err"; }
[ "$out" = "$PR_URL" ] || { ok=0; why="stdout=$out"; }
comments_call="gh api --paginate repos/o/r/issues/999/comments?per_page=100"
allowed="Read,Edit,Write,Grep,Glob,Agent,Skill,Bash(./gradlew *),\
Bash(git status *),Bash(git diff *),Bash(git log *),Bash(git show *),Bash(git add *),\
Bash(git commit *),Bash(git push *),Bash(git stash *),Bash(git checkout -- *),\
Bash(gh issue view *),Bash(gh issue comment *),Bash(gh issue create *),\
Bash(gh pr view *),Bash(gh pr diff *),Bash(gh pr create *),Bash(gh pr comment *),\
Bash(gh api repos/*/pulls/*/comments*),Bash(gh api repos/*/issues/*/comments*),\
Bash(gh api repos/*/pulls/comments/*),Bash(gh api repos/*/issues/comments/*),\
Bash(gh api repos/*/pulls/*/reviews *),Bash(gh api graphql *),Bash(.claude/scripts/pr-comments.sh *),\
Bash(.claude/scripts/check-comments.sh *),\
Bash(scripts/agent/test-stage.sh *),Bash(scripts/agent/test-pipeline.sh *),\
Bash(scripts/agent/test-runner.sh *),Bash(scripts/agent/test-requeue.sh *),Bash(shellcheck *),\
Bash(ls *),Bash(cat *),Bash(head *),Bash(tail *),Bash(grep *),Bash(find *),Bash(wc *),\
Bash(awk *),Bash(sed -n *),Bash(sort *),Bash(uniq *),Bash(diff *),Bash(jq *)"
[ "$(cat "$SHIM_EVENTS" 2>/dev/null || true)" = "gh repo view --json nameWithOwner --jq .nameWithOwner
gh api user --jq .login
gradlew --stop
$comments_call
claude
$comments_call
gh pr list --head NL-999-x --json url --jq .[0].url
gradlew --stop" ] || { ok=0; why="events=$(tr '\n' '|' 2>/dev/null < "$SHIM_EVENTS" || true)"; }
for flag in --permission-mode acceptEdits --permission-prompts none --output-format json \
            "NL-999 implement" "/flow:implement 999"; do
  argv_has "$flag" || { ok=0; why="argv lacks $flag"; }
done
[ "$(argv_after --max-budget-usd)" = 16 ] || { ok=0; why="budget=$(argv_after --max-budget-usd)"; }
settings=$(grep '/\.claude/agent/settings\.json$' "$SHIM_ARGV" 2>/dev/null || true)
[ -n "$settings" ] || { ok=0; why="argv lacks the agent settings file"; }
for verb in DELETE PATCH; do
  for rule in "Bash(gh api *-X $verb*)" "Bash(gh api *-X$verb*)" "Bash(gh api *-X=$verb*)" \
              "Bash(gh api *--method $verb*)" "Bash(gh api *--method=$verb*)"; do
    jq -e --arg rule "$rule" '.permissions.deny | index($rule)' "$settings" >/dev/null 2>&1 \
      || { ok=0; why="agent settings deny lacks $rule"; }
  done
done
[ "$(argv_after --allowedTools)" = "$allowed" ] || { ok=0; why="allowedTools=$(argv_after --allowedTools)"; }
prompt_line=$(grep -nxF -- '/flow:implement 999' "$SHIM_ARGV" 2>/dev/null | cut -d: -f1 || true)
allowed_line=$(grep -nxF -- '--allowedTools' "$SHIM_ARGV" 2>/dev/null | cut -d: -f1 || true)
{ [ -n "$prompt_line" ] && [ -n "$allowed_line" ] && [ "$prompt_line" -lt "$allowed_line" ]; } \
  || { ok=0; why="prompt must precede --allowedTools (prompt line $prompt_line, allowedTools line $allowed_line)"; }
system=$(cat "$SHIM_ARGV.system" 2>/dev/null || true)
for needle in "# Unattended run" $'\n\n## Stage: implement\n' "git push -u origin NL-999-x" "--draft" \
              "--body-file build/pr-body.md" ".github/PULL_REQUEST_TEMPLATE.md" "NL-999 "; do
  contains "$system" "$needle" || { ok=0; why="system prompt lacks '$needle'"; }
done
log="$tmp/home/.netty-loom-agent/logs/NL-999/$RUN"
[ "$(jq -r .subtype "$log/implement.json" 2>/dev/null)" = success ] || { ok=0; why="$RUN/implement.json missing or wrong"; }
grep -q "shim stderr line" "$log/implement.log" 2>/dev/null || { ok=0; why="implement.log lacks claude's stderr"; }
[ "$said" = "NL-999 implement: start|NL-999 implement: end (exit 0)|" ] || { ok=0; why="said=$said"; }
check success "$ok" "$why"
rm -rf "$tmp"

# Each failure case below is the success case minus exactly one thing, so a check the script
# drops turns precisely one of them green for the wrong reason and red here.

# --- budget ---
setup
run budget "$PR_URL" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "ended with error_max_budget_usd" || ok=0
[ "$(jq -r .subtype "$tmp/home/.netty-loom-agent/logs/NL-999/$RUN/implement.json" 2>/dev/null || true)" = error_max_budget_usd ] || ok=0
case "$said" in "NL-999 implement: start|NL-999 implement: claude ended with error_max_budget_usd"*"|NL-999 implement: end (exit 1)|") ;; *) ok=0; why="$why said=$said" ;; esac
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

# --- question: the marker comment, posted during the stage, is the outcome ---
setup
run question "$PR_URL" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 3 ] && contains "$err" "asked a question: https://github.com/o/r/issues/999#issuecomment-2" || ok=0
[ -z "$out" ] || { ok=0; why="stdout=$out"; }
! grep -q "gh pr list" "$SHIM_EVENTS" || { ok=0; why="pull request looked up after a question"; }
case "$said" in *"|NL-999 implement: end (exit 3)|") ;; *) ok=0; why="$why said=$said" ;; esac
check question "$ok" "$why"
rm -rf "$tmp"

# --- stale question: an answered marker comment older than the stage is not this stage's question ---
setup
echo '[{"user":{"login":"runner"},"body":"<!-- agent:question -->\nOld?","created_at":"2026-09-12T11:00:00Z","html_url":"u1"},
       {"user":{"login":"o"},"body":"The first.","created_at":"2026-09-12T11:30:00Z","html_url":"u2"}]' > "$SHIM_COMMENTS"
run nocommit "$PR_URL" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "no commits" || ok=0
check stale-question "$ok" "$why"
rm -rf "$tmp"

# --- pending question: the runner's marker is the newest comment before the stage, so the stage does not run ---
setup
echo '[{"user":{"login":"runner"},"body":"<!-- agent:question -->\nWhich one?","created_at":"2026-09-12T11:00:00Z","html_url":"u1"}]' > "$SHIM_COMMENTS"
run success "$PR_URL" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 3 ] && contains "$err" "question pending: u1" || ok=0
[ ! -e "$SHIM_ARGV" ] || { ok=0; why="claude ran with a question pending"; }
check pending-question "$ok" "$why"
rm -rf "$tmp"

# --- pending question, then the runner's failure comment: no owner reply, so the stage still does not run ---
setup
echo '[{"user":{"login":"runner"},"body":"<!-- agent:question -->\nWhich one?","created_at":"2026-09-12T11:00:00Z","html_url":"u1"},
       {"user":{"login":"runner"},"body":"Pipeline failed (exit 1, work).","created_at":"2026-09-12T11:30:00Z","html_url":"u2"}]' > "$SHIM_COMMENTS"
run success "$PR_URL" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 3 ] && contains "$err" "question pending: u1" || ok=0
[ ! -e "$SHIM_ARGV" ] || { ok=0; why="claude ran with a question pending"; }
check pending-question-then-commented "$ok" "$why"
rm -rf "$tmp"

# --- pending question from a third party: not the runner's, so the stage runs ---
setup
echo '[{"user":{"login":"o"},"body":"<!-- agent:question -->\nMine?","created_at":"2026-09-12T11:00:00Z","html_url":"u1"}]' > "$SHIM_COMMENTS"
run success "$PR_URL" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 0 ] && [ "$out" = "$PR_URL" ] || ok=0
check third-party-marker "$ok" "$why"
rm -rf "$tmp"

# --- question, then a crash: the comment decides, not claude's exit ---
setup
run question-crash "$PR_URL" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 3 ] && contains "$err" "asked a question" || ok=0
check question-crash "$ok" "$why"
rm -rf "$tmp"

# --- crash: no result JSON ---
setup
run crash "$PR_URL" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "no result (claude exited 1)" || ok=0
check crash "$ok" "$why"
rm -rf "$tmp"

# --- api error: is_error decides, not a subtype of success ---
setup
run api-error "" 999 review "$PR_URL" 1
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "claude ended with api_error: API Error: 403 blocked" || ok=0
check api-error "$ok" "$why"
rm -rf "$tmp"

# --- is_error with a success subtype and a clean exit ---
setup
run is-error "$PR_URL" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "claude ended with is_error (exited 0)" || ok=0
[ -z "$out" ] || { ok=0; why="stdout=$out"; }
check is-error "$ok" "$why"
rm -rf "$tmp"

# --- success result, non-zero exit: only the first line of .result reaches the failure line ---
setup
run exit-1 "$PR_URL" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "claude ended with success: Done. (exited 1)" || ok=0
! contains "$err" "second line" || { ok=0; why="whole .result in stderr: $err"; }
[ -z "$out" ] || { ok=0; why="stdout=$out"; }
check success-exit-1 "$ok" "$why"
rm -rf "$tmp"

# --- error_during_execution: infrastructure, so exit 2 ---
setup
run dropped "$PR_URL" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 2 ] && contains "$err" "claude ended with error_during_execution" || ok=0
[ -z "$out" ] || { ok=0; why="stdout=$out"; }
check dropped "$ok" "$why"
rm -rf "$tmp"

# --- gh fails before the stage: infrastructure, exit 2, claude never runs ---
setup
export SHIM_GH_FAIL="api --paginate"
run success "$PR_URL" 999 implement
unset SHIM_GH_FAIL
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 2 ] && contains "$err" "gh api --paginate failed" || ok=0
[ ! -e "$SHIM_ARGV" ] || { ok=0; why="claude ran after gh failed"; }
check gh-before "$ok" "$why"
rm -rf "$tmp"

# --- comments unreadable after the stage: logged, and the pull request and commits decide ---
setup
export SHIM_GH_FAIL="api --paginate" SHIM_GH_FAIL_AFTER=1
run success "$PR_URL" 999 implement
unset SHIM_GH_FAIL SHIM_GH_FAIL_AFTER
ok=1; why="rc=$rc stdout=$out stderr=$err"
[ "$rc" = 0 ] && [ "$out" = "$PR_URL" ] && contains "$err" "comments unreadable after the stage" || ok=0
check comments-unread "$ok" "$why"
rm -rf "$tmp"

# --- timeout ---
setup
export STAGE_TIMEOUT=1
run hang "$PR_URL" 999 implement
unset STAGE_TIMEOUT
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 124 ] && contains "$err" "timed out" || ok=0
[ ! -s "$tmp/home/.netty-loom-agent/logs/NL-999/$RUN/implement.json" ] || { ok=0; why="implement.json is not empty"; }
case "$said" in *"|NL-999 implement: end (exit 124)|") ;; *) ok=0; why="$why said=$said" ;; esac
check timeout "$ok" "$why"
rm -rf "$tmp"

# --- wrong branch ---
setup
git -C "$tmp/work" checkout -q main
run success "$PR_URL" 999 implement
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "expected NL-999-" || ok=0
[ ! -e "$SHIM_ARGV" ] || { ok=0; why="claude ran on branch main"; }
[ "$said" = "NL-999 implement: on 'main', expected NL-999-<slug>|" ] || { ok=0; why="$why said=$said"; }
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

# --- review ---
setup
run success "" 999 review "$PR_URL" 2
ok=1; why=""
[ "$rc" = 0 ] || { ok=0; why="rc=$rc stderr=$err"; }
[ -z "$out" ] || { ok=0; why="stdout=$out"; }
[ "$(cat "$SHIM_EVENTS" 2>/dev/null || true)" = "gh repo view --json nameWithOwner --jq .nameWithOwner
gh api user --jq .login
gradlew --stop
$comments_call
claude
$comments_call
gradlew --stop" ] || { ok=0; why="events=$(tr '\n' '|' 2>/dev/null < "$SHIM_EVENTS" || true)"; }
[ "$(argv_after --max-budget-usd)" = 6 ] || { ok=0; why="budget=$(argv_after --max-budget-usd)"; }
[ "$(argv_after --allowedTools)" = "$allowed" ] || { ok=0; why="allowedTools=$(argv_after --allowedTools)"; }
for flag in "NL-999 review 2" "/flow:review $PR_URL"; do
  argv_has "$flag" || { ok=0; why="argv lacks $flag"; }
done
system=$(cat "$SHIM_ARGV.system" 2>/dev/null || true)
contains "$system" "# Unattended run" || { ok=0; why="system prompt lacks unattended.md"; }
contains "$system" "--draft" && { ok=0; why="system prompt carries the implement tail"; }
log="$tmp/home/.netty-loom-agent/logs/NL-999/$RUN"
[ "$(jq -r .subtype "$log/review-2.json" 2>/dev/null)" = success ] || { ok=0; why="$RUN/review-2.json missing or wrong"; }
[ "$said" = "NL-999 review 2: start|NL-999 review 2: end (exit 0)|" ] || { ok=0; why="said=$said"; }
check review "$ok" "$why"
rm -rf "$tmp"

# --- fix ---
setup
run success "" 999 fix "$PR_URL" 1
ok=1; why=""
[ "$rc" = 0 ] || { ok=0; why="rc=$rc stderr=$err"; }
[ "$(argv_after --max-budget-usd)" = 4 ] || { ok=0; why="budget=$(argv_after --max-budget-usd)"; }
[ "$(argv_after --allowedTools)" = "$allowed" ] || { ok=0; why="allowedTools=$(argv_after --allowedTools)"; }
for flag in "NL-999 fix 1" "/flow:fix $PR_URL"; do
  argv_has "$flag" || { ok=0; why="argv lacks $flag"; }
done
grep -qxF -- "gh pr view $PR_URL --json headRefOid --jq .headRefOid" "$SHIM_EVENTS" 2>/dev/null \
  || { ok=0; why="events=$(tr '\n' '|' 2>/dev/null < "$SHIM_EVENTS" || true)"; }
[ "$(jq -r .subtype "$tmp/home/.netty-loom-agent/logs/NL-999/$RUN/fix-1.json" 2>/dev/null)" = success ] \
  || { ok=0; why="$RUN/fix-1.json missing or wrong"; }
check fix "$ok" "$why"
rm -rf "$tmp"

# --- fix leaves uncommitted edits ---
setup
run dirty "" 999 fix "$PR_URL" 1
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "uncommitted" || ok=0
check fix-dirty "$ok" "$why"
rm -rf "$tmp"

# --- fix did not push ---
setup
export SHIM_HEAD=0000000000000000000000000000000000000000
run success "" 999 fix "$PR_URL" 1
unset SHIM_HEAD
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "not pushed" || ok=0
check fix-unpushed "$ok" "$why"
rm -rf "$tmp"

# --- test ---
setup
run nocommit "" 999 test "$PR_URL"
ok=1; why=""
[ "$rc" = 0 ] || { ok=0; why="rc=$rc stderr=$err"; }
[ "$(argv_after --max-budget-usd)" = 6 ] || { ok=0; why="budget=$(argv_after --max-budget-usd)"; }
[ "$(argv_after --allowedTools)" = "$allowed" ] || { ok=0; why="allowedTools=$(argv_after --allowedTools)"; }
for flag in "NL-999 test" "/flow:test $PR_URL"; do
  argv_has "$flag" || { ok=0; why="argv lacks $flag"; }
done
[ "$(jq -r .subtype "$tmp/home/.netty-loom-agent/logs/NL-999/$RUN/test.json" 2>/dev/null)" = success ] \
  || { ok=0; why="$RUN/test.json missing or wrong"; }
check test "$ok" "$why"
rm -rf "$tmp"

# --- no run id given: the stage picks one from its start time, so a hand run overwrites nothing ---
setup
unset RUN_ID
run nocommit "" 999 test "$PR_URL"
export RUN_ID="$RUN"
runs=$(cd "$tmp/home/.netty-loom-agent/logs/NL-999" 2>/dev/null && printf '%s|' * || true)
ok=1; why="rc=$rc stderr=$err runs=$runs"
[ "$rc" = 0 ] && [[ "$runs" =~ ^$RUN_TS\|$ ]] \
  && [ "$(jq -r .subtype "$tmp/home/.netty-loom-agent/logs/NL-999/${runs%|}/test.json" 2>/dev/null)" = success ] || ok=0
check run-id-default "$ok" "$why"
rm -rf "$tmp"

# --- pull request stage without a pull request ---
setup
run success "" 999 review
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "pull request" || ok=0
[ ! -e "$SHIM_ARGV" ] || { ok=0; why="claude ran without a pull request"; }
check no-pr-argument "$ok" "$why"
rm -rf "$tmp"

exit "$failed"
