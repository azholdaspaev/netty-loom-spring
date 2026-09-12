# Agent pipeline

An issue labelled `agent/queued` becomes a pull request ready for review with no session opened
by hand. A launchd job runs `scripts/agent/runner.sh` every five minutes; each tick takes the
oldest queued issue to a worktree of its own under `../netty-loom-wt/` and runs
`scripts/agent/pipeline.sh` there: implement, then review and fix until no thread is open (three
rounds at most), then test. GitHub is the only state: labels say where an issue is, issue comments
carry questions, the pull request carries the result. Scope and the decisions behind it: #211.

## Labels

| Label | Meaning | Set by | Cleared by |
| --- | --- | --- | --- |
| `agent/queued` | waiting for a tick | maintainer; `requeue.sh` after an answer | runner, on pick-up |
| `agent/running` | a pipeline is running in the issue's worktree | runner | runner, when the pipeline returns |
| `agent/needs-input` | a question is posted on the issue | `pipeline.sh` | `requeue.sh`, once the owner has answered |
| `agent/pr-ready` | the pull request is ready for review | `pipeline.sh` | runner, after the merge |
| `agent/fix` | on a pull request: run one fix stage | maintainer | runner, after that stage |
| `agent/failed` | a stage failed; the comment has the log tail | runner | maintainer |

One tick, in order: clean up every merged pull request (worktree, local branch, the issue's
`agent/*` labels), `requeue.sh`, one fix stage per `agent/fix` pull request, then one
`agent/queued` issue. A tick that finds the lock held exits at once, so one pipeline runs at a
time; the next queued issue waits for the next free tick.

## The maintainer's two touch points

1. **A question.** The implement stage posts one comment starting with `<!-- agent:question -->`
   and the issue moves to `agent/needs-input`. Reply on the issue; the next tick moves it back to
   `agent/queued` and the pipeline starts over on the same worktree.
2. **The review.** On `agent/pr-ready`, review the pull request. Inline comments plus the
   `agent/fix` label on the pull request run one fix stage, which replies in every thread with a
   sha or the reason nothing changed, and the label comes off; label again for another round.
   Merge when satisfied: the next tick removes the worktree and branch and clears the labels.

After `agent/failed`, the comment on the issue (or pull request) holds the last 30 lines of stderr
and the log path. Replace it with `agent/queued` to retry: the worktree is reused and, once a pull
request exists, the pipeline resumes at the review stage.

## Logs

- `~/.netty-loom-agent/runner.log` — every tick's output, from launchd.
- `~/.netty-loom-agent/logs/NL-<n>/runner.log` — what the pipeline and fix stages wrote to stderr
  for that issue, appended across ticks.
- `~/.netty-loom-agent/logs/NL-<n>/<stage>[-<round>].json` and `.log` — each `claude -p` result
  (cost, duration, subtype) and its stderr, written by `stage.sh`.

## launchd

The plist lives outside the repository, at `~/Library/LaunchAgents/`. launchd starts with a bare
`PATH`, so the line below names where `gh`, `flock`, `timeout` (Homebrew) and `claude` live:

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
    <string>/opt/homebrew/bin:/Users/you/.local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
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

To stop the runner, unload it. launchd terminates a tick in flight, which releases the lock but
leaves that issue on `agent/running`; put `agent/queued` back by hand and the pipeline resumes on
the same worktree when the runner is loaded again:

```bash
launchctl unload ~/Library/LaunchAgents/io.github.azholdaspaev.netty-loom-agent.plist
```

launchd does not wake a sleeping Mac: `StartInterval` fires only while the machine is awake.
Either keep it awake during working hours (`caffeinate -s` in a terminal, released with Ctrl-C)
or accept that the pipeline runs while you do.
