# Publishing

> **Nothing here is applied automatically.** This file records state held in the Sonatype Central
> Portal and on GitHub, neither of which is under version control. Editing it changes nothing —
> check the Portal before trusting it.

Read from the Portal on 2026-08-25.

## Namespace

| |                                        |
|---|----------------------------------------|
| Namespace | `io.github.azholdaspaev`               |
| Status | Verified                               |
| Org | Azholdaspaev                           |
| Namespace ID | `9b29d13e-9976-49ba-af32-7ce6ed78bd68` |
| SNAPSHOTs | enabled                                |

Nobody verified it by hand. Sonatype provisions `io.github.<username>` automatically for an account
that signs in with GitHub, and this one does: the Portal reports **no verification history** for the
namespace, and no verification-key repository exists on the GitHub account. The manual route below
was never walked here.

The namespace id is recorded because it is what a Sonatype support ticket asks for, and it is not
recoverable without logging in (namespace row → *More Actions…* → *View ID*).

## Namespaces are tied to the sign-in method

The Portal's own banner, on the namespaces page:

> Namespaces are tied to your sign-in account. If your expected namespaces aren't shown, try another
> sign-in method. Different sign-in methods are treated as separate accounts, even for the same
> email.

**This namespace is held by the GitHub sign-in.** Signing in with Google, Microsoft or a Sonatype
username and password — even on the same email address — lands on a different account with an empty
namespace list, which looks exactly like the namespace having been lost.

## Re-verifying

Only needed to claim the namespace again under an account that does not get it automatically.

*View Namespaces* → *Add Namespace* → the Portal issues a verification key. Create a **public**
GitHub repository whose name is exactly that key (empty is fine), press *Verify Namespace*, and it
completes within minutes. Then delete the repository; it exists only for the check.

The key's value is not recorded here. It is a single-use nonce, reissued on every attempt, naming a
repository that should no longer exist.

Deleting needs the `delete_repo` scope, which this project's `gh` token does not carry:

```bash
gh auth refresh -h github.com -s delete_repo
gh repo delete azholdaspaev/<verification-key> --yes
gh auth refresh -h github.com -r delete_repo
```

The web UI does the same without widening the token.

## SNAPSHOTs must be on, and a deploy without them will not say so

Snapshots go to `https://central.sonatype.com/repository/maven-snapshots/`, and the namespace must
have them switched on: namespace row → *More Actions…* → *Enable SNAPSHOTs* → Confirm. With the
setting off, a snapshot deploy **fails with an authorization error**, which reads as a bad token
rather than as a missing setting. Snapshots are pruned after 90 days.

Sonatype validates nothing on a snapshot — no GPG signature, no sources jar, no javadoc jar — and
the snapshot endpoint speaks the ordinary Maven deploy protocol. A release does not: the Portal
takes it as a single zipped bundle over a multipart POST, which is why the build stages releases
into a local directory for the release workflow to zip and upload rather than publishing them
straight out. A snapshot needs none of that machinery, so it is the cheapest end-to-end proof that
the namespace works, and it does not wait on the signing key.

## Credentials are user tokens

Portal credentials are a username/password pair generated at
[`central.sonatype.com/usertoken`](https://central.sonatype.com/usertoken), not the login password.
**The pair cannot be retrieved once its modal closes**, so rotating means generating a replacement
rather than looking the old one up. No token is recorded here. The build reads the pair as the
`centralSnapshots` repository's credentials and the release workflow passes it to the Portal upload,
so both arrive from the `CENTRAL_PORTAL_USERNAME` / `CENTRAL_PORTAL_PASSWORD` Actions secrets,
added on 2026-08-25 (#175).

## Signing key

Read from the local keyring on 2026-08-22.

|              |                                                                 |
|--------------|-----------------------------------------------------------------|
| Fingerprint  | `7F873460471889882D06C882305DAA44A997D1FC`                       |
| Long key id  | `305DAA44A997D1FC`                                               |
| Algorithm    | RSA 4096, sign and certify only — no encryption subkey           |
| Created      | 2026-08-22                                                       |
| Expires      | 2028-08-21                                                       |
| User id      | `netty-loom-spring release signing <adilzholdaspaev@gmail.com>`  |
| Published to | `keyserver.ubuntu.com`                                           |

Project-specific, not a personal key, and sign-only: an encryption subkey would be key material
that exists only to be leaked. It lives in exactly two places — one laptop's GnuPG keyring, and the
`MAVEN_GPG_PRIVATE_KEY` / `MAVEN_GPG_PASSPHRASE` Actions secrets added on 2026-08-22. Those secrets
are write-only, so neither the key nor the passphrase is recoverable from GitHub; without a backup
elsewhere, a lost laptop forces a rotation.

`keyserver.ubuntu.com` is the one that matters — Sonatype's default lookup host. `keys.openpgp.org`
accepts a key but withholds its user id until an email round trip is completed, which Central does
not require.

### Rotation

Extend the expiry in place, on the same key, around 2028-06 rather than on the day:

```bash
gpg --quick-set-expire 7F873460471889882D06C882305DAA44A997D1FC 2y
gpg --keyserver hkps://keyserver.ubuntu.com --send-keys 7F873460471889882D06C882305DAA44A997D1FC
```

Extending preserves the fingerprint, so this table stays correct and every signature on an
already-published release keeps verifying. Generate a *replacement* key only on compromise: revoke
the old one, send the revocation, and overwrite both secrets. Central does not re-check signatures
on releases it has already accepted, so revoking does not retract them.

## How a publish happens

`.github/workflows/publish.yml`, added by
[#31](https://github.com/azholdaspaev/netty-loom-spring/issues/31). Two paths that never overlap:

| | Snapshot | Release |
|---|---|---|
| Trigger | Actions ▸ Publish ▸ *Run workflow* | push a `v*.*.*` tag |
| Version | `gradle.properties` verbatim | the tag, checked against `gradle.properties` |
| Protocol | Maven deploy to the snapshot endpoint | zipped bundle, multipart POST |
| Signed | no | yes |
| Ends at | uploaded | `VALIDATED`, awaiting your *Publish* click |

The release path uploads with `publishingType=USER_MANAGED`, so the last step is manual. That is
what makes a rehearsal possible: a `VALIDATED` deployment can still be dropped with
`DELETE /api/v1/publisher/deployment/<id>`, while a published one is on Maven Central permanently.
The bundle omits every `maven-metadata.xml` Gradle writes into `build/staging`, which the Portal
does not accept.

### Before tagging

`CHANGELOG.md`'s `## [x.y.z]` section becomes the GitHub release notes verbatim, so it has to read
as released *before* the tag exists: date the heading (`## [0.1.0] — 2026-09-01`, in place of
`— unreleased`) and rewrite the section's opening paragraph, which is written from the unreleased
side and would otherwise open the release by announcing that it is not one.

Nothing in the workflow catches this. `main` stays on `x.y.z-SNAPSHOT` by design, the version guard
compares the tag against `${declared%-SNAPSHOT}` so the suffix never trips it, and the notes guard
only fires on a section that is missing or blank.

### If the release job fails after the upload

The deployment exists on the Portal from the moment the upload step's POST returns — the id goes to
the job summary — and nothing after that can take it back: a status poll that errors, the ten-minute
timeout, and the GitHub release step all leave it pending. Re-running the job restarts it from the
first step, so it builds, stages and uploads a *second* bundle for the same coordinate. Drop the
pending one first — the `DELETE` above, or *Drop* on
[the deployments page](https://central.sonatype.com/publishing/deployments) — then re-run.

## First snapshot

The workflow's first run,
[32895087603](https://github.com/azholdaspaev/netty-loom-spring/actions/runs/32895087603) on
2026-08-25, put `0.1.0-SNAPSHOT` of all three modules on Central Snapshots as build
`20260825.202413-1`, from `0afd150`. The next day a scratch Gradle project with that repository
added resolved the starter, every transitive carrying a version (#147) — the dry run
[#31](https://github.com/azholdaspaev/netty-loom-spring/issues/31) deferred to
[#175](https://github.com/azholdaspaev/netty-loom-spring/issues/175).

[#28](https://github.com/azholdaspaev/netty-loom-spring/issues/28) had asked for that dry run as a
`publishToMavenCentral` task. No such task exists: that name belongs to the Vanniktech plugin, and
#30 used Gradle's own `maven-publish`.
