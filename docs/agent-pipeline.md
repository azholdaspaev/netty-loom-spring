# Agent pipeline

An issue labelled `agent/queued` becomes a pull request ready for review with no session opened
by hand. A launchd job runs `scripts/agent/runner.sh` every five minutes; each tick takes the
oldest queued issue to a worktree of its own under `../netty-loom-wt/` and runs
`scripts/agent/pipeline.sh` there: implement, then review and fix until no thread is open or a
round changed nothing (three rounds at most), then test. GitHub is the only state: labels say where
an issue is, issue comments carry questions, the pull request carries the result. Scope and the
decisions behind it: #211.

## Labels

| Label | Meaning | Set by | Cleared by |
| --- | --- | --- | --- |
| `agent/queued` | waiting for a tick | maintainer; `requeue.sh` after an answer | runner, on pick-up |
| `agent/running` | a pipeline is running in the issue's worktree | runner | runner, when the pipeline returns; the next tick, when the tick died — to `agent/failed`, or just off when `agent/pr-ready` or `agent/queued` is already there |
| `agent/needs-input` | a question is posted on the issue | `pipeline.sh`; `requeue.sh`, when an `agent/pr-ready` issue's newest comment is a question the stage could not read back | `requeue.sh`, once the owner has answered |
| `agent/pr-ready` | the pull request is ready for review | `pipeline.sh` | runner, after the merge, or on pick-up when the issue is queued again |
| `agent/fix` | on a pull request: run a fix stage, then a review stage | maintainer | runner, after those stages |
| `agent/retried` | the pipeline's one infrastructure failure — a stage timeout, a dropped API connection, a failed `gh` call — was retried from the worktree | runner | runner, after the merge |
| `agent/failed` | the work failed, the infrastructure failed twice, or a tick died with the issue on `agent/running`; the comment has the class and the log tail | runner | maintainer |

One tick, in order: sweep — every open `agent/running` issue to `agent/failed` with the usual
comment, since the lock proves no pipeline is running (one that also carries `agent/pr-ready`
finished its pipeline, one that also carries `agent/queued` was requeued by hand, and either
way only `agent/running` comes off), and every closed issue's `agent/*`
labels off — then clean up every merged pull request (worktree, local branch, the issue's `agent/*`
labels), `requeue.sh`, a fix stage then a review stage per `agent/fix` pull request, then one
`agent/queued` issue. A tick that finds the lock held exits at once, so one pipeline runs at a
time; the next queued issue waits for the next free tick.

## The maintainer's two touch points

1. **A question.** A stage of `pipeline.sh` posts one comment starting with
   `<!-- agent:question -->` and the issue moves to `agent/needs-input`. Reply on the issue; the
   next tick moves it back to `agent/queued` and the pipeline starts over on the same worktree.
2. **The review.** On `agent/pr-ready`, review the pull request. Inline comments plus the
   `agent/fix` label on the pull request run one fix stage, which replies in every thread with a
   sha or the reason nothing changed, then one review stage, which verifies each fix blind and
   resolves the thread or says why not; the label comes off. A finding the review stage posts
   waits for the next `agent/fix`; no test stage runs on this path. Merge when satisfied: the
   next tick removes the worktree and branch and clears the labels.

A pipeline that fails on the infrastructure — `stage.sh` exits 124 on a timeout, 2 when a `gh`
call failed or `claude` ended with `error_during_execution`; `pipeline.sh` exits 2 when one of its
own `gh` calls failed, the runner when `./gradlew dependencySources` did — goes back to
`agent/queued` with `agent/retried` and no comment, once; every other failure, and a second
infrastructure failure, is `agent/failed`. The comment on the issue (or pull request) then names the class and holds the
last 30 lines of stderr and the log path. Replace `agent/failed` with `agent/queued` to retry: the
worktree is reused and, once a pull request exists, the pipeline resumes at the review stage.
`agent/retried` stays on until the merge, so a hand retry after it gets no second automatic one.

## Logs

- `~/.netty-loom-agent/runner.log` — every tick's output, from launchd.
- `~/.netty-loom-agent/logs/NL-<n>/runner.log` — what the pipeline and the `agent/fix` stages
  wrote to stderr for that issue, appended across ticks.
- `~/.netty-loom-agent/logs/NL-<n>/<stage>[-<round>].json` and `.log` — each `claude -p` result
  (cost, duration, subtype) and its stderr, written by `stage.sh`.

## Cost

`stage.sh` caps each `claude -p`: implement 16 USD, review 6, fix 4, test 6, 45 minutes each;
`pipeline.sh` runs at most three review/fix rounds, so one issue is bounded by
16 + 3 × (6 + 4) + 6 = 52 USD, and stops early when a fix stage pushed no commit and the review
after it posted no inline comment: the same model on the same code returns the same verdicts
(#262). A stage that hits its cap ends with `error_max_budget_usd` and the issue goes to
`agent/failed`.

Baseline: #239, the first issue to go from `agent/queued` to a merged pull request (#249) with no
session opened by hand, on 2026-09-13 with `main` at `a0eecdc`.

| Stage | USD | Wall | Turns | Denied calls |
| --- | --- | --- | --- | --- |
| implement | 2.94 | 6 min | 50 | 4 |
| review 1 — nothing to post | 1.24 | 2 min | 14 | 2 |
| test | 4.73 | 13 min | 68 | 3 |
| `agent/fix`, one thread from the maintainer | 0.88 | 2 min | 20 | 2 |
| review 1 again — resolved that thread, posted one | 1.80 | 4 min | 26 | 3 |
| fix 1 | 1.05 | 2 min | 22 | 3 |
| review 2 — resolved it, nothing new | 1.71 | 3 min | 20 | 4 |
| test again | 5.22 | 11 min | 77 | 5 |

19.58 USD and 43 minutes of stage time. From label to `agent/pr-ready`: 28 minutes the first
time, 25 the second, each including up to five minutes for the tick and one for
`dependencySources`. The second run was the `agent/queued` detour that #253 replaced: the pipeline
had no shorter path to a review stage that settles a maintainer's thread, so it paid for a test
stage as well. With both passes running under the 6 USD review budget, no review came near it.

Of the 26 denied calls, 21 were Bash commands that no allow rule matches as a whole — `;`, `|`,
`&&`, `for`, a heredoc, a `VAR=… ./gradlew` prefix — and the stage got the same facts another
way each time; three were `gh api` reads of pull request comments (#245); one was
`gh issue create --label`, denied by design, so the ticket named its labels in its body; one an
IntelliJ terminal call, not on the tool list. Two more findings: inside the stage's sandbox
`/usr/bin/java` resolves no JDK, so the implement stage compiled and ran the tests by hand until
the plist below named one; and a re-run test stage does not see the tickets its earlier run
opened (#250 and #252 are the same gap; #254).

## launchd

The plist lives outside the repository, at `~/Library/LaunchAgents/`. launchd starts with a bare
`PATH`, so the line below names where `gh`, `flock`, `timeout` (Homebrew), `claude` and the JDK
live — the same JDK the shell uses, first:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>io.github.azholdaspaev.netty-loom-agent</string>
  <key>ProgramArguments</key>
  <array>
    <string>/Users/you/Git/netty-loom-spring/scripts/agent/runner.sh</string>
  </array>
  <key>WorkingDirectory</key>
  <string>/Users/you/Git/netty-loom-spring</string>
  <key>StartInterval</key>
  <integer>300</integer>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key>
    <string>/Users/you/.sdkman/candidates/java/current/bin:/opt/homebrew/bin:/Users/you/.local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
  </dict>
  <key>StandardOutPath</key>
  <string>/Users/you/.netty-loom-agent/runner.log</string>
  <key>StandardErrorPath</key>
  <string>/Users/you/.netty-loom-agent/runner.log</string>
</dict>
</plist>
```

```bash
launchctl load ~/Library/LaunchAgents/io.github.azholdaspaev.netty-loom-agent.plist
```

To stop the runner, unload it. launchd terminates a tick in flight, which releases the lock and
leaves that issue on `agent/running`; the first tick after the runner is loaded again moves it to
`agent/failed` with the usual comment, and `agent/queued` resumes the pipeline on the same
worktree. A reboot or a `kill -9` recovers the same way:

```bash
launchctl unload ~/Library/LaunchAgents/io.github.azholdaspaev.netty-loom-agent.plist
```

launchd does not wake a sleeping Mac: `StartInterval` fires only while the machine is awake.
Either keep it awake during working hours (`caffeinate -s` in a terminal, released with Ctrl-C)
or accept that the pipeline runs while you do.
