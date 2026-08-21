# wheelhouse

A fantasy football game where you spin a wheel to assemble a roster, and a live scoring
platform underneath it. Working name.

## The game

Four positions: one QB, one RB, two flex. Each is assembled from **several different players
of that position**: your quarterback is passing yards from one, touchdowns from another,
rushing yards from a third. Seventeen players in all.

The wheel decides who you get. You decide which of the position's remaining parts to spend
them on, and each part goes only once per position, so the last pick takes whatever is left
and an early choice costs a later one.

You choose against **projections**, never results. Actual numbers are withheld by the server
until every pick is in, then the whole roster resolves at once against what really happened
and prints as a slip. Respins are a budget for the whole build (three team, three player)
rather than one per pick.

| Slot | Parts |
|---|---|
| QB (4) | Arm (passing yards) · Shoulders (passing TDs) · Legs (rushing yards) · Cleats (rushing TDs) |
| RB (5) | Legs (rushing yards) · Hands (receptions) · Chest (receiving yards) · Nose (total TDs) · Motor (carries) |
| Flex (4) | Hands (receptions) · Chest (receiving yards) · Nose (total TDs) · Eyes (targets) |

Every weight is standard PPR except `rb.motor` and `flex.eyes`. Carries and targets are not
scored by any real league, so those two multipliers are invented rather than inherited, and
they are the next candidates for removal.

## Status

Playable, and playable without signing up for anything. Open `http://localhost:8080`, press
play, and build a roster one pick at a time: spin a team, spin a player, choose what you take
from them. Both wheels animate. Entries are stored in a file-backed database and survive a
restart; an archived week rehydrates itself from ESPN when an old entry is resumed.

Weeks come from ESPN, so preseason, regular season and playoffs all work without a calendar
to maintain. The week locks at its first kickoff.

### Historic mode

Pick any of the last five completed seasons and play a week that already happened. Week 10 of
2023 hands you Tommy DeVito starting for the Giants and a Trey McBride breakout.

The range is five seasons rather than everything ESPN has, because the game is played against
projections and Sleeper only publishes those from 2019. A season without forecasts is
unplayable rather than merely old.

This exists for three reasons. It makes the game playable in the offseason, it lets a week be
tested in seconds instead of waiting for Sunday, and old rosters are genuinely funny.

Drafting blind against projections is what keeps a finished week honest: you see the same
forecast anyone would have had before kickoff, and the results only arrive once you have
committed every pick. Leaderboards are per week, so an archived week has its own and never
mixes with a live one.

### How you played

The score says how the wheel treated you; it does not say how you played. So a finished roster
also reports what the best possible arrangement of parts would have been **for the exact players
you were dealt**, and your score as a percentage of it. Luck divides out, because the ceiling
moves with your draw.

Each position is a small assignment problem: parts and players are equal in number and each part
goes once, so it is a perfect matching over at most five elements and brute force is cheaper than
an algorithm. The same pass finds the single swap that would have gained the most, which is the
part anybody actually learns from.

### Reading the form

Every option carries the player's last six games at that exact stat, and clicking one opens
the full table: every stat as a column, every prior game as a row, with the average, this
week's projection, and how often he cleared it underneath. Reading down a column tells you
about the stat; reading across a row tells you about the matchup.

Nothing from the week being drafted is ever included. ESPN's game log returns the whole
season, which for an archived contest contains the answer, so the filter is strict and lives
in one place.

### The slip

A finished roster prints as a betting slip: every pick with its raw stat and points, position
subtotals, projected against final. It is the only light surface in the product, which is the
point — it reads as an object you were handed rather than another panel.

## Running it

Requires JDK 21. It is installed at the Homebrew path below but is keg-only, so `JAVA_HOME`
has to point at it explicitly.

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./mvnw spring-boot:run
```

Add that export to `~/.zshrc` to stop typing it.

Out of season every game on the scoreboard has already kicked off, so the current week is
locked and no roster can be built. To exercise the flow anyway:

```
./mvnw spring-boot:run -Dspring-boot.run.arguments=--wheelhouse.contest.enforce-lock=false
```

That flag defaults to on and must stay on in production. An unlocked week means someone can
build a roster after seeing the results.

## What it does right now

Every 30 seconds it asks ESPN which games are in progress. Every 15 seconds it pulls a full
box score for each live game, parses it, and diffs it against the previous reading to
produce stat deltas.

Endpoints, all temporary scaffolding:

| Endpoint | Shows |
|---|---|
| `GET /api/live` | Games currently in progress |
| `GET /api/games/{eventId}` | Every stat being tracked for one game |
| `GET /api/deltas?limit=50` | What changed most recently |
| `GET /api/wheel/slots` | Roster shape and each slot's options |
| `GET /api/wheel/teams?slot=FLEX` | Teams the wheel can land on |
| `GET /api/wheel/players?slot=FLEX&team=SF` | Who a team spin resolves to |
| `GET /api/play/contest` | Current week and lock time |
| `POST /api/play/open?owner=` | Open or resume this week's entry |
| `POST /api/play/open?owner=&season=&week=` | Open an archived week |
| `GET /api/play/archive` | Which seasons the archive can reach |
| `POST /api/play/{id}/pick/{i}/team?respin=` | Spin a team |
| `POST /api/play/{id}/pick/{i}/player?respin=` | Spin a player |
| `POST /api/play/{id}/pick/{i}/choose?option=` | Take a body part |
| `GET /api/play/leaderboard` | This week's standings |
| `GET /api/play/mine?ids=` | Summaries for entries you already hold |

## Design notes

**Snapshots, not events.** ESPN serves cumulative totals rather than a play stream, so
ingestion polls and diffs. This is the better shape for reliability: a missed poll is caught
up by the next one, a duplicate poll produces an empty diff, and a restart resumes from the
last stored snapshot. Idempotency falls out of the data shape instead of being something we
have to enforce.

**Stats are mapped by key, never by index.** ESPN gives each box score category a `keys`
array and each athlete a positional `stats` array. The categories have different shapes and
the order is not contractual.

**Compound stats get split.** The key `completions/passingAttempts` arrives with the value
`"23/35"`, and `sacks-sackYardsLost` arrives as `"3-27"`. The separator is detected on the
key rather than the value, so a negative number like `-3` rushing yards is never mistaken
for a compound.

**The User-Agent matters.** ESPN's edge rejects unrecognised user agents, including Java's
default `Java-http-client/21`, and also rejects strings that claim to be a browser without
matching browser fingerprints. An honest identifying string with a contact URL passes. It is
set in `application.properties`. Sleeper rejects Python's `urllib` default the same way.

**The espn_id crosswalk covers less than half the league.** 4,467 of 9,412 active players,
and the missing half is overwhelmingly rookies, who are exactly the players filling preseason
box scores. So `AthleteResolver` learns the mapping from the box scores themselves instead:
every athlete who plays arrives with an id, a name and a team, which is enough to match
against the catalog by normalised name. It only has to resolve players who actually play,
which is precisely the set that can have scored anything.

**An entry id is a bearer token.** Every pick endpoint accepts one on its own and asks nothing
else, which is fine for a link you send a friend and only fine while the id stays secret. It was
not secret: the leaderboard published one for every player on it, and a lookup by display name
handed back the rest. A name printed on a public board was therefore enough to read somebody's
roster and spend their respins. The board no longer carries ids, the name lookup is gone, and
`/mine` takes the ids the caller already holds, so there is nothing left to enumerate.

**Actuals are withheld on the server, not hidden by the page.** While a roster is being
built the API response contains projections and nothing else: no actual values, no actual
points, and no `total` key at all. A blind draft that ships the answers in the same payload is
theatre, and the leaderboard withholds incomplete entries' scores for the same reason.

**Projections are deliberately not routed through `StatKey` or `AthleteResolver`.** Those are
built on ESPN athlete ids, which are only learned once a player has appeared in a box score.
Before kickoff that is nobody, so routing forecasts through them would leave the entire wheel
reading zero.

**Sleeper's `espn_id` is dead for modern players.** It is null for everyone whose rookie year
is 2021 or later: 0 of 367 for 2021, 0 of 840 for 2024. Trevor Lawrence, Ja'Marr Chase, Puka
Nacua and Jayden Daniels all have none. Anything keyed on it silently drops the majority of
relevant players and gets worse every season, which is why `AthleteResolver` learns the
mapping from box scores by name and team instead.

**Points come from the scored roster, never by searching the options list.** The options now
carry projections, so deriving displayed points from them would have quietly converted the
entire scoreboard to forecast numbers with no error anywhere.

**The projections endpoint needs the season type passed through.** It was hardcoded to
`regular`. Asking for regular-season data while playing a preseason week returns a full,
plausible payload for the wrong games, which is far worse than returning nothing. Preseason
and postseason have no projections at all, and the UI says so rather than showing zeroes.

**Scoring is standard PPR, chosen over fitted weights on purpose.** A touchdown is six and a
hundred yards is ten because that is what every fantasy player already expects. Fitted
multipliers balanced the choice far better but produced opaque numbers (receiving touchdowns
came out at ×31.5) that nobody could sanity check, and a score you cannot read is worse than
one that is imperfectly balanced.

The cost is that on expectation the choice is lopsided: passing yards beat passing touchdowns
for essentially every quarterback, rushing yards win for 98% of running backs, receiving
yards for 90% of receivers. What keeps the decision alive is variance. Passing yards barely
differ between starters (223 ± 17) while touchdowns swing 0/4/8/12, so taking the touchdown
option is a bet on the ceiling rather than a mistake. Real results bear this out: in testing,
a back's 2 receptions (2.0) beat both his 19 rushing yards (1.9) and his 15 receiving yards
(1.5).

`tools/calibrate.py` reports how lopsided each slot currently is, and can still solve for
balanced weights if that trade ever looks worth making.

**Completions were cut from the QB slot.** Every starting quarterback projects to about
the same completions (20 ± 1.3) and the same passing yards (223 ± 17). Spread of 0.07 and
0.08, against 0.37 for flex options. The choice would have been decided by noise. Rushing
yards has the widest spread of any stat in the game at 0.65, ranging from about 2 to 39 a
week, which turns the slot into a real question.

**Finished games are polled exactly once.** A game that ends while nobody is watching still
has to have its final box score captured, or every roster pointing at it scores zero forever.

**Spins are written on first request and never rewritten.** Otherwise refreshing is a free
re-roll. `spins` is append-only and is the audit trail; the outcome is also derived from a
seed built out of entry, slot and attempt, so the same spin always resolves the same way.

**Historic weeks reuse the entire live pipeline.** ESPN's scoreboard accepts a season, season
type and week, and an archived summary has exactly the same box score shape as a live one, so
historic mode needed no new parsing at all. It is the live ingestion path pointed at a date.

**Stats are keyed by contest, not just by game.** Without that, an archived 2007 week and this
Sunday would write into the same map and score each other's rosters.

**An archived week builds its own player pool from the box scores.** Sleeper's roster is the
league as it exists today, so it is useless for 2007. The box score already names everyone who
took a snap, which is a better pool anyway: you can only draft someone who was on a field.
Positions are not in the payload, so they are inferred from what each player did, with volume
thresholds so a wildcat snap does not turn a receiver into a running back.

**Resume remembers the entry, not the name.** Reopening by name alone always resolves to the
live week, which silently threw you out of whatever archive week you were playing.

**Entries load their slots eagerly.** An entry is never useful without them, there are always
exactly four, and `open-in-view` is off, so lazy loading fails the moment the transaction that
read the entry closes.

**Calibrating on frequency turns rare stats into lotteries.** This is why fitted weights were
abandoned. Receiving touchdowns happen 0.32 times a week, so balancing on frequency priced
one at 31.5 points, four times the next best option. Standard scoring prices it at 6, which
is both legible and proportionate.

## Data sources

| Source | Used for | Notes |
|---|---|---|
| ESPN site API | Live box scores, schedule, week calendar | Undocumented, no key, UA-sensitive |
| Sleeper API | Player table and weekly projections | Documented, free; its `espn_id` is unusable for modern players, see below |
| nflverse | Historical play-by-play for the replayer | Not wired up yet |

Sleeper's `search_rank` is what keeps the wheel playable. Filtering to rostered, active
players at QB/RB/WR/TE with `search_rank < 400` gives roughly 130 WR, 92 RB, 53 QB, 52 TE.
Without that filter the wheel is full of practice squad linemen.

## Test fixture

`src/test/resources/fixtures/summary-401873279.json` is a real box score captured from the
4th quarter of a live 2026 preseason game. Develop the parser against it instead of waiting
for kickoff.

## Next

- **Accounts.** The entry id is now the capability: unguessable, held in localStorage, and the
  only thing that reaches a draft. That is enough for a shared link and not enough to follow you
  between devices. OAuth through Supabase is the next step, and it only needs a deployed
  callback URL to start.
- **Drop the two invented multipliers.** `rb.motor` (carries) and `flex.eyes` (targets) are
  not scored by real leagues, so their weights are made up. Removing both takes the roster to
  fourteen picks, which is a noticeably shorter game; worth playing seventeen first.
- **Decide whether the lopsided choice still needs a fix.** Standard scoring means the highest
  expected option is nearly always the same one. The uniqueness rule is now in (each part goes
  once per position), which turns the question into which player gets the good part. Worth
  playing a few weeks before deciding whether anything further is needed.
- **The replayer**: stream a finished game through the pipeline at speed, so scoring can be
  developed out of season and the determinism claim can actually be tested.
- Accounts, so a name is not the only identity. Deliberately deferred: the game has to be
  playable in under a minute from a shared link.

Open design question: the QB slot's team spin is mostly theatre. Fifteen of thirty-two teams
have exactly one eligible quarterback, so the spin resolves to a forced pick. Probably the
QB slot should wheel straight to a player and skip the team stage, which also buys back
interactions against the sixty-second budget. RB averages 2.8 options per team and flex 5.3,
so both keep the two-stage flow.
