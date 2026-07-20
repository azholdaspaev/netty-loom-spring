# Branch rulesets

Versioned copies of the two branch rulesets protecting the default branch.

> **These files are not applied automatically.** Editing one changes nothing until someone
> re-applies it by hand with the `PUT` below. A merged edit to this directory does **not**
> mean the live gate changed — check the live config before trusting these files.

## Live rulesets

| File | Ruleset | Id |
|---|---|---|
| `main-pull-request.json` | `main-pull-request` | `19270013` |
| `main-required-ci.json` | `main-required-ci` | `19270019` |

### Applying: `PUT`, never `POST`

```bash
gh api -X PUT repos/azholdaspaev/netty-loom-spring/rulesets/19270013 \
  --input .github/rulesets/main-pull-request.json
```

`POST /repos/{owner}/{repo}/rulesets` **creates a new ruleset** — GitHub does not treat
`name` as a unique key, so it will happily leave you with two active rulesets called
`main-pull-request`. They are then evaluated as a union, which is confusing to diagnose and
unwindable only by hand in the web UI. That is the reason the ids above are recorded here
rather than left in a PR description.

Read live config back with `gh api repos/azholdaspaev/netty-loom-spring/rulesets/{id}`.
Note that `GET` returns fields these files omit (`id`, `node_id`, `created_at`,
`updated_at`, `source`, `source_type`, `_links`, `current_user_can_bypass`, plus
`required_reviewers` / `allowed_merge_methods` / `do_not_enforce_on_create` inside rule
parameters), so a naive diff against a file is permanently dirty.

## Why two rulesets

A bypass applies to the **whole ruleset**, not per-rule. Splitting is the only way to scope
one.

GitHub forbids self-approval, so on a solo repo the "1 approval" requirement would be
unsatisfiable without a bypass. If the approval rule and the CI rule lived in one ruleset,
that bypass would also skip CI. Splitting keeps the build gate real:

| | `main-pull-request` | `main-required-ci` |
|---|---|---|
| Rules | PR + 1 approval, dismiss stale, no force-push, no deletion, linear history | both build matrix cells |
| Bypass | admin, `pull_request` mode | none |

Net effect: direct pushes to the default branch are blocked for everyone including the
owner; the owner can merge their own unapproved PR; nobody merges a red build.

## `actor_id: 5`

`5` is the built-in **Admin** repository role.

GitHub does not document this mapping anywhere — the request for it
([`github/rest-api-description#4406`](https://github.com/github/rest-api-description/issues/4406))
is open and unanswered. The value is derived empirically by configuring a bypass in the web
UI and reading the id back. Do not assume neighbouring ids follow a hierarchy; they are
arbitrary internal row ids, not ordered by privilege.

Corroboration for this repo: `GET /rulesets/19270013` reports
`current_user_can_bypass: "pull_requests_only"` for the owner, who is an admin.

## Required checks are pinned to the Actions app

Each entry in `main-required-ci.json` carries `"integration_id": 15368` — the
`github-actions` app.

Without it, GitHub matches required checks on context *name* across both the Checks API
(what Actions writes) and the older Commit Statuses API, which anyone holding
`statuses: write` can post to. A single `gh api -X POST .../statuses/$SHA -f state=success
-f context='build (ubuntu-latest)'` would then satisfy the gate that this ruleset's empty
`bypass_actors` exists to make unbypassable. Pinning the app means only real Actions runs
count.

Verify the id with:

```bash
gh api repos/azholdaspaev/netty-loom-spring/commits/$SHA/check-runs \
  --jq '.check_runs[] | "\(.name) \(.app.id) \(.app.slug)"'
```

## Known gaps

**`required_linear_history` is not actually enforced against the admin.** The bypass in
`main-pull-request.json` is ruleset-scoped, so `bypass_mode: "pull_request"` waives every
rule in that ruleset when merging via a PR — not just the approval requirement it was added
for. Linear history holds today only because the repo-level `allow_merge_commit` setting is
`false`, and that setting lives in the web UI, **not in version control**: flipping it
re-opens merge commits on the default branch with no file change and no PR to review.

Pinning the ruleset's own `allowed_merge_methods` would not help, since a whole-ruleset
bypass waives that field too. The only complete fix is moving `deletion`,
`non_fast_forward`, and `required_linear_history` into a third ruleset with no bypass
actors — deliberately not done, as three rulesets is more moving parts than the risk
warrants on a solo repo.

**No drift detection.** Nothing verifies that these files match live config. See the
`apply.sh` follow-up issue.

## Note on naming

Both rulesets target `~DEFAULT_BRANCH`, not a literal `main`. This is intentional —
enforcement survives a default-branch rename. The file and ruleset names still say `main`,
so after such a rename the names would be misleading while the behaviour stays correct.
