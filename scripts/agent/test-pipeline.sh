#!/usr/bin/env bash
# Exercise scripts/agent/pipeline.sh against shims for stage.sh and gh in a scratch repository.
# Usage: scripts/agent/test-pipeline.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PR_URL="https://github.com/o/r/pull/7"
failed=0

export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid

# pipeline.sh finds stage.sh and pr-comments.sh relative to itself, so each case gets a copy of
# the tree's layout beside a stage.sh shim, a clone on NL-999-x, gh first on PATH and a fresh HOME.
# The stage shim writes the result file pipeline.sh sums, moves the pull request head on the fix
# rounds SHIM_FIX_PUSHES lists ("1,0" = fix 1 pushes a commit, fix 2 does not; unset = every fix
# pushes), posts inline comments as the runner on the review rounds SHIM_REVIEW_POSTS counts
# ("0,1" = review 2 posts one; unset = none), edits src.txt and adds scratch.txt in the stage
# SHIM_DIRTY names, and
# exits 1 from the stage SHIM_FAIL names or 3 from the one SHIM_QUESTION names ("review 2"). The
# gh shim serves that state back, inline comments
# only through pr-comments.sh's own call, and answers each GraphQL thread query with the count
# SHIM_OPEN holds for the latest review round ("2,0" = two open threads after the first review,
# none after the second).
setup() {
  tmp=$(mktemp -d)
  mkdir -p "$tmp/scripts/agent" "$tmp/.claude/scripts" "$tmp/bin" "$tmp/home" "$tmp/state"
  cp "$HERE/pipeline.sh" "$tmp/scripts/agent/pipeline.sh"
  cp "$HERE/../../.claude/scripts/pr-comments.sh" "$tmp/.claude/scripts/pr-comments.sh"
  git init -q --bare -b main "$tmp/origin"
  git clone -q "$tmp/origin" "$tmp/work" 2>/dev/null
  echo root > "$tmp/work/src.txt"
  git -C "$tmp/work" add src.txt
  git -C "$tmp/work" commit -q -m "root"
  git -C "$tmp/work" push -q origin HEAD:main
  git -C "$tmp/work" checkout -q -b NL-999-x

  cat > "$tmp/scripts/agent/stage.sh" <<'SHIM'
#!/usr/bin/env bash
echo "stage $*" >> "$SHIM_EVENTS"
stage=$2; round=${4:-}
if [ "$stage" = "${SHIM_DIRTY:-}" ]; then echo mutated >> src.txt; touch scratch.txt; fi
if [ "$stage${round:+ $round}" = "${SHIM_FAIL:-}" ]; then
  echo "stage.sh: NL-$1 $stage: claude ended with error_max_budget_usd" >&2; exit 1
fi
if [ "$stage${round:+ $round}" = "${SHIM_QUESTION:-}" ]; then
  echo "stage.sh: NL-$1 $stage: asked a question: https://github.com/o/r/issues/999#issuecomment-2" >&2; exit 3
fi
if [ "$stage" = implement ] && [ "${SHIM_IMPLEMENT_RC:-0}" != 0 ]; then exit "$SHIM_IMPLEMENT_RC"; fi
mkdir -p "$HOME/.netty-loom-agent/logs/NL-$1"
echo '{"subtype":"success","total_cost_usd":0.5,"duration_ms":60000}' \
  > "$HOME/.netty-loom-agent/logs/NL-$1/$stage${round:+-$round}.json"
case "$stage" in
  implement) echo "$SHIM_URL" ;;
  review)
    echo "$round" > "$SHIM_STATE/review-round"
    posts=$(echo "${SHIM_REVIEW_POSTS:-}" | cut -d, -f"$round")
    jq -c --argjson n "${posts:-0}" '. as $c | $c + [range($n) | {user: {login: "runner"},
        created_at: "2026-01-01T00:00:\(($c | length) + . | tostring | ("0" + .)[-2:])Z"}]' \
      "$SHIM_STATE/comments" > "$SHIM_STATE/comments.new" && mv "$SHIM_STATE/comments.new" "$SHIM_STATE/comments" ;;
  fix)
    pushes=$(echo "${SHIM_FIX_PUSHES-1,1,1}" | cut -d, -f"$round")
    if [ "${pushes:-0}" = 1 ]; then echo "fix-$round" > "$SHIM_STATE/head"; fi ;;
esac
SHIM
  cat > "$tmp/bin/gh" <<'SHIM'
#!/usr/bin/env bash
echo "gh $*" >> "$SHIM_EVENTS"
case "$*" in
  "pr list --head NL-999-x "*) if [ -n "${SHIM_PR_URL:-}" ]; then echo "$SHIM_PR_URL"; fi ;;
  "pr view "*" --json url "*) echo "$3" ;;
  "pr view "*" --json headRefOid "*) cat "$SHIM_STATE/head" ;;
  "api user --jq .login") echo runner ;;
  "api --paginate repos/o/r/pulls/7/comments?per_page=100 --jq "*) jq "$5" "$SHIM_STATE/comments" ;;
  "api --paginate "*) echo "[]" ;;
  "api graphql "*)
    round=$(cat "$SHIM_STATE/review-round" 2>/dev/null || echo 1)
    open=$(echo "$SHIM_OPEN" | cut -d, -f"$round")
    [ -n "$open" ] || { echo "gh shim: SHIM_OPEN exhausted" >&2; exit 1; }
    jq -n --argjson n "$open" '[range($n) | {id: "T\(.)", isResolved: false, isOutdated: false, firstCommentId: .}]' ;;
  "issue edit 999 "*) echo "https://github.com/o/r/issues/999" ;;
  "issue comment 999 --body-file -") cat > "$SHIM_STATE/comment"; echo "https://github.com/o/r/issues/999#issuecomment-1" ;;
esac
SHIM
  chmod +x "$tmp/scripts/agent/stage.sh" "$tmp/bin/gh"
  echo implement > "$tmp/state/head"
  echo '[{"user": {"login": "maintainer"}, "created_at": "2025-12-31T00:00:00Z"}]' > "$tmp/state/comments"
  export SHIM_EVENTS="$tmp/events" SHIM_STATE="$tmp/state" SHIM_URL="$PR_URL"
}

# run <open counts> <pr-url-or-empty>; sets rc, out, err, comment, stages
run() {
  rc=0
  out=$(cd "$tmp/work" && SHIM_OPEN=$1 SHIM_PR_URL=$2 HOME=$tmp/home PATH="$tmp/bin:$PATH" \
        "$tmp/scripts/agent/pipeline.sh" 999 2> "$tmp/stderr") || rc=$?
  err=$(cat "$tmp/stderr")
  comment=$(cat "$SHIM_STATE/comment" 2>/dev/null || true)
  stages=$(grep '^stage \|^gh pr ready\|^gh issue' "$SHIM_EVENTS" 2>/dev/null | tr '\n' '|' || true)
}

check() {
  local name=$1 cond=$2 detail=$3
  if [ "$cond" = 1 ]; then echo "ok $name"; else echo "FAIL $name: $detail"; failed=1; fi
}

contains() { case "$1" in *"$2"*) return 0 ;; *) return 1 ;; esac; }

IMPLEMENT="stage 999 implement|"
R1="stage 999 review $PR_URL 1|"; F1="stage 999 fix $PR_URL 1|"
R2="stage 999 review $PR_URL 2|"; F2="stage 999 fix $PR_URL 2|"
R3="stage 999 review $PR_URL 3|"; F3="stage 999 fix $PR_URL 3|"
TEST="stage 999 test $PR_URL|"
HANDOFF="gh pr ready $PR_URL|gh issue edit 999 --add-label agent/pr-ready|gh issue comment 999 --body-file -|"
NEEDS_INPUT="gh issue edit 999 --remove-label agent/running --add-label agent/needs-input|"

# --- converges at the second review ---
setup
run 2,0 ""
ok=1; why=""
[ "$rc" = 0 ] || { ok=0; why="rc=$rc stderr=$err"; }
[ "$out" = "$PR_URL" ] || { ok=0; why="stdout=$out"; }
[ "$stages" = "$IMPLEMENT$R1$F1$R2$TEST$HANDOFF" ] || { ok=0; why="stages=$stages"; }
for needle in "$PR_URL" "rounds: 2" "converged" "2.50 USD" "5 min"; do
  contains "$comment" "$needle" || { ok=0; why="comment lacks '$needle': $comment"; }
done
contains "$comment" "did not converge" && { ok=0; why="comment says it did not converge"; }
check converges "$ok" "$why"
rm -rf "$tmp"

# --- never converges ---
setup
run 1,1,1 ""
ok=1; why=""
[ "$rc" = 0 ] || { ok=0; why="rc=$rc stderr=$err"; }
[ "$stages" = "$IMPLEMENT$R1$F1$R2$F2$R3$F3$TEST$HANDOFF" ] || { ok=0; why="stages=$stages"; }
for needle in "did not converge after 3 rounds; 1 thread open" "4.00 USD" "8 min"; do
  contains "$comment" "$needle" || { ok=0; why="comment lacks '$needle': $comment"; }
done
check no-convergence "$ok" "$why"
rm -rf "$tmp"

# --- a round that changes nothing ends the loop ---
setup
export SHIM_FIX_PUSHES=0
run 1,1 ""
unset SHIM_FIX_PUSHES
ok=1; why=""
[ "$rc" = 0 ] || { ok=0; why="rc=$rc stderr=$err"; }
[ "$stages" = "$IMPLEMENT$R1$F1$R2$TEST$HANDOFF" ] || { ok=0; why="stages=$stages"; }
for needle in "rounds: 2" "did not converge" "fix 1 pushed no commit and review 2 posted no comment" "1 thread open" "2.50 USD"; do
  contains "$comment" "$needle" || { ok=0; why="comment lacks '$needle': $comment"; }
done
contains "$comment" "after 3 rounds" && { ok=0; why="comment blames the round cap: $comment"; }
check stalls "$ok" "$why"
rm -rf "$tmp"

# --- a fix that moved the head earns the next round ---
setup
export SHIM_FIX_PUSHES=1,0
run 1,1,1 ""
unset SHIM_FIX_PUSHES
ok=1; why=""
[ "$rc" = 0 ] || { ok=0; why="rc=$rc stderr=$err"; }
[ "$stages" = "$IMPLEMENT$R1$F1$R2$F2$R3$TEST$HANDOFF" ] || { ok=0; why="stages=$stages"; }
contains "$comment" "fix 2 pushed no commit and review 3 posted no comment" || { ok=0; why="comment: $comment"; }
check moved-head-continues "$ok" "$why"
rm -rf "$tmp"

# --- a review that posted a comment earns the next round ---
setup
export SHIM_FIX_PUSHES=0 SHIM_REVIEW_POSTS=0,1
run 1,1,1 ""
unset SHIM_FIX_PUSHES SHIM_REVIEW_POSTS
ok=1; why=""
[ "$rc" = 0 ] || { ok=0; why="rc=$rc stderr=$err"; }
[ "$stages" = "$IMPLEMENT$R1$F1$R2$F2$R3$TEST$HANDOFF" ] || { ok=0; why="stages=$stages"; }
contains "$comment" "fix 2 pushed no commit and review 3 posted no comment" || { ok=0; why="comment: $comment"; }
check new-comment-continues "$ok" "$why"
rm -rf "$tmp"

# --- nothing to fix ---
setup
run 0 ""
ok=1; why=""
[ "$rc" = 0 ] || { ok=0; why="rc=$rc stderr=$err"; }
[ "$stages" = "$IMPLEMENT$R1$TEST$HANDOFF" ] || { ok=0; why="stages=$stages"; }
contains "$comment" "rounds: 1" || { ok=0; why="comment lacks 'rounds: 1': $comment"; }
check clean-review "$ok" "$why"
rm -rf "$tmp"

# --- pull request already open: no implement stage ---
setup
run 0 "$PR_URL"
ok=1; why=""
[ "$rc" = 0 ] || { ok=0; why="rc=$rc stderr=$err"; }
[ "$stages" = "$R1$TEST$HANDOFF" ] || { ok=0; why="stages=$stages"; }
contains "$comment" "1.00 USD" || { ok=0; why="comment lacks '1.00 USD': $comment"; }
check resumes "$ok" "$why"
rm -rf "$tmp"

# --- implement asked a question ---
setup
export SHIM_QUESTION=implement
run 0 ""
unset SHIM_QUESTION
ok=1; why="rc=$rc stdout=$out stderr=$err stages=$stages"
[ "$rc" = 0 ] && [ -z "$out" ] \
  && [ "$stages" = "$IMPLEMENT$NEEDS_INPUT" ] || ok=0
check question "$ok" "$why"
rm -rf "$tmp"

# --- a review asked a question: same label move, no hand-off ---
setup
export SHIM_QUESTION="review 2"
run 1,1 ""
unset SHIM_QUESTION
ok=1; why="rc=$rc stdout=$out stderr=$err stages=$stages comment=$comment"
[ "$rc" = 0 ] && [ -z "$out" ] && [ -z "$comment" ] \
  && [ "$stages" = "$IMPLEMENT$R1$F1$R2$NEEDS_INPUT" ] || ok=0
check review-question "$ok" "$why"
rm -rf "$tmp"

# --- the test stage asked a question ---
setup
export SHIM_QUESTION=test
run 0 ""
unset SHIM_QUESTION
ok=1; why="rc=$rc stdout=$out stderr=$err stages=$stages comment=$comment"
[ "$rc" = 0 ] && [ -z "$out" ] && [ -z "$comment" ] \
  && [ "$stages" = "$IMPLEMENT$R1$TEST$NEEDS_INPUT" ] || ok=0
check test-question "$ok" "$why"
rm -rf "$tmp"

# --- the test stage asked a question with the tree dirty: reset, so the resume starts clean ---
setup
export SHIM_QUESTION=test SHIM_DIRTY=test
run 0 ""
unset SHIM_QUESTION SHIM_DIRTY
ok=1; why="rc=$rc stderr=$err stages=$stages tree=$(git -C "$tmp/work" status --porcelain)"
[ "$rc" = 0 ] && [ -z "$(git -C "$tmp/work" status --porcelain)" ] \
  && [ "$stages" = "$IMPLEMENT$R1$TEST$NEEDS_INPUT" ] || ok=0
check test-question-dirty "$ok" "$why"
rm -rf "$tmp"

# --- implement asked a question mid-work: its edits stay for the resumed implement ---
setup
export SHIM_QUESTION=implement SHIM_DIRTY=implement
run 0 ""
unset SHIM_QUESTION SHIM_DIRTY
ok=1; why="rc=$rc stderr=$err stages=$stages tree=$(git -C "$tmp/work" status --porcelain)"
[ "$rc" = 0 ] && [ "$(git -C "$tmp/work" status --porcelain)" = $' M src.txt\n?? scratch.txt' ] \
  && [ "$stages" = "$IMPLEMENT$NEEDS_INPUT" ] || ok=0
check implement-question-dirty "$ok" "$why"
rm -rf "$tmp"

# --- implement failed or timed out: the code passes through untouched ---
for stage_rc in 1 124; do
  setup
  export SHIM_IMPLEMENT_RC=$stage_rc
  run 0 ""
  unset SHIM_IMPLEMENT_RC
  ok=1; why="rc=$rc stdout=$out stages=$stages"
  [ "$rc" = "$stage_rc" ] && [ -z "$out" ] && [ "$stages" = "$IMPLEMENT" ] || ok=0
  check "implement-exit-$stage_rc" "$ok" "$why"
  rm -rf "$tmp"
done

# --- a later stage fails: no hand-off ---
setup
export SHIM_FAIL="review 2"
run 1,1 ""
unset SHIM_FAIL
ok=1; why="rc=$rc stderr=$err"
[ "$rc" = 1 ] && contains "$err" "review: claude ended with error_max_budget_usd" || ok=0
[ "$stages" = "$IMPLEMENT$R1$F1$R2" ] || { ok=0; why="stages=$stages"; }
[ -z "$comment" ] || { ok=0; why="issue comment posted: $comment"; }
check stage-failure "$ok" "$why"
rm -rf "$tmp"

# --- the test stage leaves the tree dirty ---
setup
export SHIM_DIRTY=test
run 0 ""
unset SHIM_DIRTY
ok=1; why=""
[ "$rc" = 0 ] || { ok=0; why="rc=$rc stderr=$err"; }
[ -z "$(git -C "$tmp/work" status --porcelain)" ] || { ok=0; why="tree still dirty: $(git -C "$tmp/work" status --porcelain)"; }
for needle in "git checkout -- . && git clean -fd" " M src.txt" "?? scratch.txt"; do
  contains "$comment" "$needle" || { ok=0; why="comment lacks '$needle': $comment"; }
done
check dirty-after-test "$ok" "$why"
rm -rf "$tmp"

exit "$failed"
