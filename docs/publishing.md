# Publishing

> **Nothing here is applied automatically.** This file records state held in the Sonatype Central
> Portal and on GitHub, neither of which is under version control. Editing it changes nothing —
> check the Portal before trusting it.

Read from the Portal on 2026-08-13.

## Namespace

| |                                        |
|---|----------------------------------------|
| Namespace | `io.github.azholdaspaev`               |
| Status | Verified                               |
| Org | Azholdaspaev                           |
| Namespace ID | `9b29d13e-9976-49ba-af32-7ce6ed78bd68` |
| SNAPSHOTs | not enabled                            |

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

## SNAPSHOTs are off, and a failed deploy will not say so

Snapshots go to `https://central.sonatype.com/repository/maven-snapshots/`, and the namespace must
have them switched on: namespace row → *More Actions…* → *Enable SNAPSHOTs* → Confirm. Until then a
snapshot deploy **fails with an authorization error**, which reads as a bad token rather than as a
missing setting. Snapshots are pruned after 90 days.

Sonatype validates nothing on a snapshot — no GPG signature, no sources jar, no javadoc jar — and
the snapshot endpoint speaks the ordinary Maven deploy protocol. A release does not: the Portal
takes it as a single zipped bundle over a multipart POST, which is why the build stages releases
into a local directory for #31 to upload rather than publishing them straight out. A snapshot needs
none of that machinery, so it is the cheapest end-to-end proof that the namespace works, and it does
not wait on the signing key.

## Credentials are user tokens

Portal credentials are a username/password pair generated at
[`central.sonatype.com/usertoken`](https://central.sonatype.com/usertoken), not the login password.
**The pair cannot be retrieved once its modal closes**, so rotating means generating a replacement
rather than looking the old one up. No token is recorded here, and nothing in the build reads one
yet — the only publishing repository it declares is a local directory. Wiring the token in is #31.

## Not done yet

The namespace is the only part of publishing that is out-of-band. The rest is repository work, and
[#30](https://github.com/azholdaspaev/netty-loom-spring/issues/30) has landed: `maven-publish` and
`signing` are configured in the root build for `core`, `mvc` and the starter, each publishing a jar,
a sources jar and a javadoc jar into the root project's `build/staging`. The examples are not
published. Nothing reaches Sonatype yet.

- [#29](https://github.com/azholdaspaev/netty-loom-spring/issues/29) — the GPG key itself. The
  build already reads `MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE` and requires a signature
  for any non-`-SNAPSHOT` version, so what is missing is the key and the CI secrets, not the wiring.
- [#31](https://github.com/azholdaspaev/netty-loom-spring/issues/31) — the release workflow, which
  is what actually uploads, and where the token above gets wired in.

[#28](https://github.com/azholdaspaev/netty-loom-spring/issues/28) also asked for a
`publishToMavenCentral` snapshot dry run. No such task exists: that name belongs to the Vanniktech
plugin, and #30 used Gradle's own `maven-publish`, whose tasks stop at `build/staging`. The dry run
moved to #31 along with the upload.
