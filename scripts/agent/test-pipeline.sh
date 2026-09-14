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
# The stage shim records the tree it found in tree-<stage>, writes the result file pipeline.sh
# sums, moves the pull request head on the fix
# rounds SHIM_FIX_PUSHES lists ("1,0" = fix 1 pushes a commit, fix 2 does not; unset = every fix
# pushes), posts inline comments as the runner on the review rounds SHIM_REVIEW_POSTS counts
# ("0,1" = review 2 posts one; unset = none), edits src.txt -- staged and unstaged -- and adds
# scratch.txt in the stage SHIM_DIRTY names, and
# exits SHIM_FAIL_RC (1 unset) from the stage SHIM_FAIL names or 3 from the one SHIM_QUESTION names ("review 2"). The
# gh shim serves that state back, inline comments
# only through pr-comments.sh's own call, the issue's comments from issue-comments, keeps the
# body of a pull request pipeline.sh opens itself in pr-body, and answers each GraphQL thread query with the count
# SHIM_OPEN holds for the latest review round ("2,0" = two open threads after the first review,
# none after the second); every call whose arguments start with SHIM_GH_FAIL exits 1 instead, from
# the first event line starting with SHIM_GH_FAIL_AFTER on when that is set.
setup() {
  tmp=$(mktemp -d)
  mkdir -p "$tmp/scripts/agent" "$tmp/.claude/scripts" "$tmp/.github" "$tmp/bin" "$tmp/home" "$tmp/state"
  cp "$HERE/pipeline.sh" "$tmp/scripts/agent/pipeline.sh"
  cp "$HERE/../../.claude/scripts/pr-comments.sh" "$tmp/.claude/scripts/pr-comments.sh"
  cp "$HERE/../../.github/PULL_REQUEST_TEMPLATE.md" "$tmp/.github/PULL_REQUEST_TEMPLATE.md"
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
git status --porcelain > "$SHIM_STATE/tree-$stage"
if [ "$stage" = "${SHIM_DIRTY:-}" ]; then
  echo staged >> src.txt; git add src.txt; echo mutated >> src.txt; touch scratch.txt
fi
if [ "$stage${round:+ $round}" = "${SHIM_FAIL:-}" ]; then
  echo "stage.sh: NL-$1 $stage: claude ended with error_max_budget_usd" >&2; exit "${SHIM_FAIL_RC:-1}"
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
if [ -n "${SHIM_GH_FAIL:-}" ] && { [ -z "${SHIM_GH_FAIL_AFTER:-}" ] || grep -q "^$SHIM_GH_FAIL_AFTER" "$SHIM_EVENTS"; }; then
  case "$*" in "$SHIM_GH_FAIL"*) echo "gh: dial tcp: no route to host" >&2; exit 1 ;; esac
fi
case "$*" in
  "pr list --head NL-999-x "*) if [ -n "${SHIM_PR_URL:-}" ]; then echo "$SHIM_PR_URL"; fi ;;
  "pr create --draft --title "*" --body-file -") cat > "$SHIM_STATE/pr-body"; echo "$SHIM_URL" ;;
  "pr view "*" --json url "*) echo "$3" ;;
  "pr view "*" --json headRefOid "*) cat "$SHIM_STATE/head" ;;
  "api user --jq .login") echo runner ;;
  "api --paginate repos/o/r/pulls/7/comments?per_page=100 --jq "*) jq "$5" "$SHIM_STATE/comments" ;;
  "api --paginate repos/o/r/issues/999/comments?per_page=100") cat "$SHIM_STATE/issue-comments" ;;
  "api --paginate "*) echo "[]" ;;
  "repo view --json nameWithOwner --jq .nameWithOwner") echo o/r ;;
  "api graphql "*)
    round=$(cat "$SHIM_STATE/review-round" 2>/dev/null || echo 1)
    open=$(echo "$SHIM_OPEN" | cut -d, -f"$round")
    [ -n "$open" ] || { echo "gh shim: SHIM_OPEN exhausted" >&2; exit 1; }
    jq -n --argjson n "$open" '[range($n) | {id: "T\(.)", isResolved: false, isOutdated: false, firstCommentId: .}]' ;;
  "issue view 999 --json title --jq .title") echo "Decide the retry" ;;
  "issue edit 999 "*) echo "https://github.com/o/r/issues/999" ;;
  "issue comment 999 --body-file -") cat > "$SHIM_STATE/comment"; echo "https://github.com/o/r/issues/999#issuecomment-1" ;;
esac
SHIM
  chmod +x "$tmp/scripts/agent/stage.sh" "$tmp/bin/gh"
  echo implement > "$tmp/state/head"
  echo '[{"user": {"login": "maintainer"}, "created_at": "2025-12-31T00:00:00Z"}]' > "$tmp/state/comments"
  echo '[]' > "$tmp/state/issue-comments"
  export SHIM_EVENTS="$tmp/events" SHIM_STATE="$tmp/state" SHIM_URL="$PR_URL"
}

# run <open counts> <pr-url-or-empty>; sets rc, out, err, comment, stages
run() {
  rc=0
  out=$(cd "$tmp/work" && SHIM_OPEN=$1 SHIM_PR_URL=$2 HOME=$tmp/home PATH="$tmp/bin:$PATH" \
        "$tmp/scripts/agent/pipeline.sh" 999 2> "$tmp/stderr") || rc=$?
  err=$(cat "$tmp/stderr")
  comment=$(cat "$SHIM_STATE/comment" 2>/dev/null || true)
  stages=$(grep '^stage \|^gh pr create\|^gh pr ready\|^gh issue' "$SHIM_EVENTS" 2>/dev/null | tr '\n' '|' || true)
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
CREATE="gh issue view 999 --json title --jq .title|gh pr create --draft --title NL-999 Decide the retry --body-file -|"
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
contains "$comment" "stash" && { ok=0; why="comment names a stash on a clean pick-up: $comment"; }
check converges "$ok" "$why"
rm -rf "$tmp"

# --- a retry on a dirty worktree: stashed by name, so the stage starts clean and the maintainer can find the hunks ---
setup
echo staged >> "$tmp/work/src.txt"; git -C "$tmp/work" add src.txt
echo mutated >> "$tmp/work/src.txt"; touch "$tmp/work/scratch.txt"
run 0 ""
stash=$(git -C "$tmp/work" stash list --format=%gs)
tree=$(cat "$SHIM_STATE/tree-implement" 2>/dev/null || echo unread)
ok=1; why="rc=$rc stderr=$err stages=$stages stash=$stash tree=$tree comment=$comment"
[ "$rc" = 0 ] && [ "$stages" = "$IMPLEMENT$R1$TEST$HANDOFF" ] && [ -z "$tree" ] \
  && [ "$(git -C "$tmp/work" stash list | wc -l | tr -d ' ')" = 1 ] \
  && contains "$stash" "NL-999 retry 20" \
  && contains "$err" "${stash#On NL-999-x: }" && contains "$comment" "${stash#On NL-999-x: }" || ok=0
check retry-dirty "$ok" "$why"
rm -rf "$tmp"

# --- a retry on commits without a pull request: pushed and opened by the script, no implement stage ---
setup
echo work >> "$tmp/work/src.txt"; git -C "$tmp/work" commit -qam "NL-999 work"
run 0 ""
body=$(cat "$SHIM_STATE/pr-body" 2>/dev/null || true)
ok=1; why="rc=$rc stderr=$err stages=$stages body=$body"
[ "$rc" = 0 ] && [ "$out" = "$PR_URL" ] && [ "$stages" = "$CREATE$R1$TEST$HANDOFF" ] \
  && [ "$(git -C "$tmp/origin" rev-parse refs/heads/NL-999-x)" = "$(git -C "$tmp/work" rev-parse HEAD)" ] || ok=0
for needle in "Closes #999." "no implement stage wrote" "## Problem" "## What changed" "## Verification" "## Checklist"; do
  contains "$body" "$needle" || { ok=0; why="body lacks '$needle': $body"; }
done
contains "$comment" "1.00 USD" || { ok=0; why="comment lacks '1.00 USD': $comment"; }
check retry-unpushed "$ok" "$why"
rm -rf "$tmp"

# --- the same commits after an answered question: implement runs, the one stage that reads the answer ---
setup
echo work >> "$tmp/work/src.txt"; git -C "$tmp/work" commit -qam "NL-999 work"
echo '[{"user": {"login": "runner"}, "body": "<!-- agent:question --> Which?"},
       {"user": {"login": "o"}, "body": "The first."}]' > "$tmp/state/issue-comments"
run 0 ""
ok=1; why="rc=$rc stderr=$err stages=$stages"
[ "$rc" = 0 ] && [ "$out" = "$PR_URL" ] && [ "$stages" = "$IMPLEMENT$R1$TEST$HANDOFF" ] \
  && [ -z "$(git -C "$tmp/origin" rev-parse -q --verify refs/heads/NL-999-x)" ] || ok=0
check retry-unpushed-answered "$ok" "$why"
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
[ "$rc" = 0 ] && [ "$(git -C "$tmp/work" status --porcelain)" = $'MM src.txt\n?? scratch.txt' ] \
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

# --- a gh call of the pipeline's own fails: infrastructure, exit 2, no hand-off ---
setup
export SHIM_GH_FAIL="pr ready"
run 0 ""
unset SHIM_GH_FAIL
ok=1; why="rc=$rc stderr=$err stages=$stages comment=$comment"
[ "$rc" = 2 ] && contains "$err" "gh pr ready failed" && [ -z "$comment" ] \
  && [ "$stages" = "$IMPLEMENT$R1${TEST}gh pr ready $PR_URL|" ] || ok=0
check gh-failure "$ok" "$why"
rm -rf "$tmp"

# --- the question's own label edit fails: the work's code, not 2, so a retry cannot rerun the stage past its pending question ---
setup
export SHIM_QUESTION="review 2" SHIM_GH_FAIL="issue edit 999 --remove-label agent/running --add-label agent/needs-input"
run 1,1 ""
unset SHIM_QUESTION SHIM_GH_FAIL
ok=1; why="rc=$rc stderr=$err stages=$stages comment=$comment"
[ "$rc" = 1 ] && [ -z "$comment" ] && [ "$stages" = "$IMPLEMENT$R1$F1$R2$NEEDS_INPUT" ] || ok=0
check question-label-failure "$ok" "$why"
rm -rf "$tmp"

# --- the head check after a fix fails on its gh call: the same class, and no next round ---
setup
export SHIM_GH_FAIL="pr view $PR_URL --json headRefOid" SHIM_GH_FAIL_AFTER="stage 999 fix"
run 1,0 ""
unset SHIM_GH_FAIL SHIM_GH_FAIL_AFTER
ok=1; why="rc=$rc stderr=$err stages=$stages comment=$comment"
[ "$rc" = 2 ] && contains "$err" "gh pr view failed" && [ -z "$comment" ] && [ "$stages" = "$IMPLEMENT$R1$F1" ] || ok=0
check head-check-failure "$ok" "$why"
rm -rf "$tmp"

# --- pr-comments.sh fails on its gh call: the same class ---
setup
export SHIM_GH_FAIL="pr view $PR_URL --json url"
run 0 ""
unset SHIM_GH_FAIL
ok=1; why="rc=$rc stderr=$err stages=$stages"
[ "$rc" = 2 ] && contains "$err" "pr-comments.sh failed" && [ "$stages" = "$IMPLEMENT" ] || ok=0
check pr-comments-failure "$ok" "$why"
rm -rf "$tmp"

# --- a later stage is killed or dropped mid-edit (124, 2): the tree is reset, so the retry's review starts clean ---
for stage_rc in 124 2; do
  setup
  export SHIM_FAIL=test SHIM_FAIL_RC=$stage_rc SHIM_DIRTY=test
  run 0 ""
  unset SHIM_FAIL SHIM_FAIL_RC SHIM_DIRTY
  ok=1; why="rc=$rc stderr=$err stages=$stages tree=$(git -C "$tmp/work" status --porcelain)"
  [ "$rc" = "$stage_rc" ] && [ -z "$(git -C "$tmp/work" status --porcelain)" ] \
    && [ "$stages" = "$IMPLEMENT$R1$TEST" ] && [ -z "$comment" ] || ok=0
  check "test-exit-$stage_rc-dirty" "$ok" "$why"
  rm -rf "$tmp"
done

# --- implement killed mid-edit: its edits stay for its retried self, as on a question ---
setup
export SHIM_IMPLEMENT_RC=124 SHIM_DIRTY=implement
run 0 ""
unset SHIM_IMPLEMENT_RC SHIM_DIRTY
ok=1; why="rc=$rc stderr=$err stages=$stages tree=$(git -C "$tmp/work" status --porcelain)"
[ "$rc" = 124 ] && [ "$(git -C "$tmp/work" status --porcelain)" = $'MM src.txt\n?? scratch.txt' ] \
  && [ "$stages" = "$IMPLEMENT" ] || ok=0
check implement-exit-124-dirty "$ok" "$why"
rm -rf "$tmp"

# --- a later stage fails on the work mid-edit: the tree stays for the maintainer to read ---
setup
export SHIM_FAIL=test SHIM_DIRTY=test
run 0 ""
unset SHIM_FAIL SHIM_DIRTY
ok=1; why="rc=$rc stderr=$err stages=$stages tree=$(git -C "$tmp/work" status --porcelain)"
[ "$rc" = 1 ] && [ "$(git -C "$tmp/work" status --porcelain)" = $'MM src.txt\n?? scratch.txt' ] \
  && [ "$stages" = "$IMPLEMENT$R1$TEST" ] || ok=0
check test-exit-1-dirty "$ok" "$why"
rm -rf "$tmp"

# --- the test stage leaves the tree dirty ---
setup
export SHIM_DIRTY=test
run 0 ""
unset SHIM_DIRTY
ok=1; why=""
[ "$rc" = 0 ] || { ok=0; why="rc=$rc stderr=$err"; }
[ -z "$(git -C "$tmp/work" status --porcelain)" ] || { ok=0; why="tree still dirty: $(git -C "$tmp/work" status --porcelain)"; }
for needle in "MM src.txt" "?? scratch.txt"; do
  contains "$comment" "$needle" || { ok=0; why="comment lacks '$needle': $comment"; }
done
check dirty-after-test "$ok" "$why"
rm -rf "$tmp"

exit "$failed"
