# ADR 0003 — Method naming: `should` tests, no articles, one closed vocabulary

- Status: Accepted
- Date: 2026-09-13
- Amended: 2026-09-23 ([#206](https://github.com/azholdaspaev/netty-loom-spring/issues/206))
- Rule: `CLAUDE.md` § Guidelines, rule 7 (the rule itself; this record holds only its rationale)

## Context

No written rule governed method names (#279), and the agent pipeline writes most new tests, so a
new test copied whichever style its neighbouring file used. A survey of the tree at `0896e27~1`
(821 `@Test` methods, 274 non-`@Override` production methods) found:

- Three test styles chosen per file — `should…` (334), a leading-article sentence (181, mvc and
  starter only) and `<subject><Verb>…` (306) — and one file mixing two of them. 475 test names
  carried an article; 61 exceeded 60 characters.
- Production names carried no article and were verb-first throughout, but drifted in
  vocabulary: `require*` beside `verify*` and `checkValid` for guards, `fire*` beside `notify*`
  for listener callbacks, `create`/`build*`/`new*`/`from` for construction, `getX` beside bare
  `x()` on one class, and two methods one letter's case apart on `NettyWebServer`.

Nothing enforced any of it; the one enforcement pattern in the repository was
`.claude/scripts/check-comments.sh`.

## Decision

Rule 7 in `CLAUDE.md` § Guidelines is the decision. The choices behind it:

- **`should` rather than subject-verb.** `should…` was the largest single style, and a fixed
  prefix makes the countable part scriptable — a test name either starts with `should` or it
  does not, where "the subject comes first" needs a reader to know the subject.
- **No articles.** Production already had none. Tests drift towards them because a test name
  reads as a sentence and prose feels natural there; the article adds length and no information,
  and its absence is countable.
- **The vocabulary is closed** for the same reason rule 5's trigger list is: an open list is no
  list. A verb the table does not name is added to the table, never to a file, so that the next
  synonym is a review finding rather than a fourth spelling of the same concept.
- **The migration runs per module** — #280 (core), #281 (mvc), #282 (starter and the examples,
  and the wiring of `check-naming.sh` into `check` and the `PostToolUse` hook), #283 (production
  vocabulary) — because the hook would otherwise fail every edit of a test file that is not yet
  renamed.

Outside the `mark*` row: `HttpConnectionRegistry.dispatchFinished` is an event callback the
handlers deliver to the registry, in the shape of Netty's own `channelActive`/`channelInactive`,
not a command to record a transition. It keeps its name.

## Consequences

- Reviewers quote rule 7's bullets and the table by row; an article, a filler prefix, a name
  over 60 characters or a verb from the **Not** column is a finding.
- `.claude/scripts/check-naming.sh` counts what can be counted. Whether a `require*` throws, and
  whether one concept keeps one verb in a file, stay with the reviewer.

## Amendments

### 2026-09-23 — `exchangeStarted` became a command (#206)

`HttpConnectionRegistry.exchangeStarted` now returns whether the exchange is admitted and closes
an idle connection while draining, so it is no longer a callback. It is `admitExchange`, and the
list of callbacks that keep their names was corrected in place.
