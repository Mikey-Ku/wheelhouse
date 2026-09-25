# wheelhouse

A fantasy football game where you spin a wheel to assemble a roster, and a live scoring
platform underneath it. Working name.

## The game

Four positions: one QB, one RB, two flex. Each is assembled from **several different players
of that position**: your quarterback is passing yards from one, touchdowns from another,
rushing yards from a third. Fourteen players in all.

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
| RB (4) | Legs (rushing yards) · Hands (receptions) · Chest (receiving yards) · Nose (total TDs) |
| Flex (3) | Hands (receptions) · Chest (receiving yards) · Nose (total TDs) |

**Every weight is standard PPR.** That rule cost three parts. Completions, carries and targets
are all real and all predictive, and no fantasy league pays for any of them, so their weights
had to be invented rather than inherited, and a made-up number is the wrong thing to ask
somebody to bet a pick on.

Cutting them made the game harder as well as shorter. Measured over 17,552 player-weeks,
receptions, targets and carries were the only parts that beat their projection more than half
the time. What is left is yardage at roughly 47% and touchdowns at far less.

## Status

Playable at `http://localhost:8080`. Five pages share one nav:

| Page | What it is |
|---|---|
| Play (`/`) | This week's slates with a countdown to the next lock, past weeks, and the draft itself. A roster lives at `/?r=<id>`; a shared slip at `/?s=<token>`. |
| Live (`/live.html`) | Every game this week, grouped by slate: score, clock, network, and each started game's passing, rushing and receiving leaders. Your own rosters for the week sit on top. |
| Leaderboards (`/leaderboards.html`) | One board per slate and one per past week. Each profile's best roster counts. |
| Profile (`/profile.html`) | Sign in with Google or an email address, change your name, and every roster you have played. |
| Ops (`/ops.html`) | The ingestion view. Not linked. |

Anyone can play straight away as a guest: pressing Play makes a guest session, with no form.
Guest rosters are kept and shown on the slip, but stay off the leaderboards. Making a profile
(through Supabase Auth: Google, or an email and password) turns the guest into it and brings
every roster along; signing in to an existing profile from a guest session does the same. Rosters belong to whoever played them, so
an id in the address bar reads or changes nothing for anyone else. Everything is stored in a file-backed database and survives a restart; an archived week
rehydrates itself from ESPN when an old roster is opened.

Weeks come from ESPN, so preseason, regular season and playoffs all work without a calendar
to maintain.

### Slates

A live week is split by day, and each part locks at its own first kickoff:

| Slate | Games | Roster |
|---|---|---|
| Sunday | every Sunday game, 1pm through Sunday night | Classic, 14 picks |
| Thursday Night, Monday Night, any other day | that day's games | Showdown, 7 picks |

A week that locked at its first kickoff was closed from Thursday night until the following
week, which is exactly when people want to play. Slates are derived from ESPN's kickoff times
(in Eastern), so Thanksgiving, Saturday doubleheaders and international Friday games fall out
without a calendar.

**A slate's wheel only lands on its own teams.** By Sunday the Thursday teams have already
played, and a wheel that could still reach them would hand out results. This also keeps bye
teams off the wheel.

**A showdown is QB ×2, RB ×2, Flex ×3.** A classic roster needs four different quarterbacks
and a single game has two. In the backfield positions there are fewer picks than parts, so
part of the decision is which parts to leave empty. The showdown pool is everyone projected
for at least three points at their best part rather than the league-wide relevance cutoff,
which would drop a backup starting in place of an injured quarterback.

**Entries keep the week as their contest id.** Stats, projections and scoring are keyed by
week and every slate's games are in that week, so none of them know slates exist. The slate
decides the lock, the teams, the roster shape and which leaderboard an entry is on.

A finished live roster reads "pending" rather than zero until its games kick off, and the
Live tab shows your rosters scoring as the games are played. The ingestion view that used to
live there is at `/ops.html`.

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
goes once, so it is a perfect matching over at most four elements and brute force is cheaper than
an algorithm.

Only the number comes back, never the arrangement that reaches it. A week can be played again and
the wheel deals differently every time, so printing the answer on the way out would replace the
second run with a copying exercise.

### Reading the form

Every option carries the player's last six games at that exact stat, and clicking one opens
the full table: every stat as a column, every prior game as a row, with the average, this
week's projection, and how often he cleared it underneath. Reading down a column tells you
about the stat; reading across a row tells you about the matchup.

Nothing from the week being drafted is ever included. ESPN's game log returns the whole
season, which for an archived contest contains the answer, so the filter is strict and lives
in one place.

### The slip

A finished roster prints as a slip: one line per pick and one number per line. Before
kickoff that number is the projection; once a game starts it is the score, with the projection
under it in green or red. The corner shows your rank on the board once there is a field, and
the slip shares through a read-only link that never carries the roster's id.

## Tests

```sh
./mvnw test                          # everything, 73 tests
./mvnw test -DexcludedGroups=network  # the 61 not tagged network
```

Twelve of them replay a real week and therefore need ESPN and Sleeper to be reachable;
they are tagged `network` so CI can skip them. The rest run offline in under a second.

What they pin, and why each one exists:

| Suite | Guards |
|---|---|
| `SlotTest` | Every part is a stat standard PPR actually pays for. Three have already been cut for failing that rule. |
| `BoxscoreParserTest` | Reads by key not index, splits compound keys, and parses `-3` yards as a number rather than a two-part key. |
| `FormWindowTest` | The form guide never returns the week being drafted. A leak here is silent and destroys the game. |
| `WithholdingTest` | A mid-draft payload carries no actuals, and the leaderboard publishes no entry ids. Both have been broken before. |
| `CaptureRateTest` | Your score can never exceed the ceiling, and the arrangement that reaches it is never reported. |
| `LiveWeekDryRunTest` | The live path, driven a poll at a time. See below. |
| `SlateTest` | A week splits into Thursday, Sunday and Monday against the real week 3 scoreboard, each locks at its own kickoff, and Sunday never offers a team that played Thursday. |
| `SlateDraftTest` | Every slate in the current week drafts to completion inside its own games, including a showdown filled from one game. |
| `GuestTest` | A guest plays and stays off the board; making a profile or signing in to one brings the guest's rosters along; a guest cannot claim a name. |
| `AccountTest` | A first sign-in makes a profile and signs you in; signing in again keeps the name you chose; a taken name gets the next free number and the name check says so first; a token Supabase does not vouch for signs nobody in; playing needs a profile. |
| `OwnershipTest` | Nobody else can read or spin your roster by its id, a made pick cannot be re-rolled or reassigned, and a rename reaches your rosters. |
| `SharedSlipTest` | A share link is a separate token: the shared view carries no entry id, and the token opens nothing for writing. |
| `ReleasedWeekTest` | A draft keeps working after its archived week is released from memory. A load run found spins failing with "no team has an eligible QB left" once more weeks were in play than the cap holds. |

### The live-week dry run

The archive path reads a final box score once. The live path reads the same game repeatedly
while it changes, and those are different problems: the differ only earns its keep on the second
reading, a missed poll has to be caught up by the next one, and a stat correction arrives as a
number going *down*. None of that is exercised by replaying a week that is already over, which is
why the live path had never executed at all.

`ReplayService` takes a finished box score and emits it as a sequence of partial ones, so the
live path can be driven on a Tuesday in August instead of discovered on the first Sunday of the
season.

```sh
./mvnw test -Dtest=DryRunReportTest    # prints a replay rather than only asserting one
```

```
  624 stats across 79 players

  stage  state   magnitude     deltas
  ------------------------------------
  1      in            282        624
  2      in            651        132
  ...
  8      post         3040        296

  final reading matches the real box score: true
```

What the assertions pin: deltas on every poll, a duplicate poll costing nothing, a skipped poll
caught up by the next one, a correction moving a number down rather than being ignored, and the
last reading equalling the authoritative box score exactly.

Building it found a modelling error worth recording. The first version dropped stats that floored
to zero, on the theory that a receiver with no catches is not in the box score yet. He is: ESPN
lists everyone who has taken the field, zeroes included. Dropping them made the final poll
announce a flood of changes that never happened, and made the last reading differ from the
reading it is supposed to equal.

## Deploying

Runs anywhere that can run a JVM. It is not serverless-compatible: there is a scheduler and a
database. That includes Vercel, which runs Dockerfiles now but as request handlers: it scales an
idle container to zero after five minutes, runs no background workers, and keeps no state between
instances, so the ESPN poller stops and each instance holds its own copy of the live box scores.

`render.yaml` is a Render Blueprint for this: Docker, Virginia beside the Supabase pooler, and the
readiness probe as the health check. It prompts for the datasource values on creation.

```sh
docker build -t wheelhouse .
docker run -p 8080:8080 wheelhouse
```

Everything is environment-driven with local defaults, so a fresh clone needs no configuration
and a host needs no code change:

| Variable | Default | Notes |
|---|---|---|
| `PORT` | `8080` | Most hosts assign this. Without binding it every request 502s. |
| `SPRING_DATASOURCE_URL` | local H2 file | Point at Postgres in production; container disks are ephemeral. |
| `SPRING_DATASOURCE_USERNAME` | `sa` | |
| `SPRING_DATASOURCE_PASSWORD` | empty | Set it in the host's dashboard, never in the repo. |
| `SUPABASE_URL` | empty | `https://<ref>.supabase.co`. Empty means guests only; the sign-in form says it isn't set up. |
| `SUPABASE_PUBLISHABLE_KEY` | empty | Project Settings → API Keys. Public by design: the page is given it to start a sign-in. |

### Sign-in

The page signs in with Supabase and hands the access token to `POST /api/account/supabase`
once. The server asks Supabase whose it is and swaps it for its own session cookie, so no
signing secret lives here. In the Supabase dashboard:

1. **Authentication → URL Configuration.** Site URL is the deployed address. Add
   `https://<render-host>/**` and `http://localhost:8080/**` to the redirect URLs.
2. **Authentication → Providers → Email.** Turn off **Confirm email** unless custom SMTP is set
   up. Supabase's built-in mailer only reaches the project's own team, two messages an hour.
3. **Authentication → Providers → Google.** A Google Cloud OAuth client (Web application), with
   `https://<ref>.supabase.co/auth/v1/callback` as its redirect URI, and its ID and secret here.

Putting the publishable key in a page makes Supabase's Data API reachable, and by default it
serves every table in the public schema. `DataApiLockdown` closes it on every boot: row-level
security on, the `anon` and `authenticated` grants revoked, and the defaults changed so tables
added later start closed. The app connects as the tables' owner and is unaffected.

`/actuator/health` is already exposed for health checks. The app boots in under two seconds, so
a slow first request on a free tier is the platform waking a container, not the application
starting.

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
| `GET /api/play/slates` | This week's slates, their games and lock times |
| `POST /api/play/open?slate=` | Open a roster for a slate (the next open one if omitted). Needs a profile. |
| `POST /api/play/open?season=&week=` | Open a roster for a past week. Needs a profile. |
| `GET /api/play/archive` | Which seasons the archive can reach |
| `POST /api/play/{id}/pick/{i}/team?respin=` | Spin a team |
| `POST /api/play/{id}/pick/{i}/player?respin=` | Spin a player |
| `POST /api/play/{id}/pick/{i}/choose?option=` | Take a body part |
| `GET /api/play/leaderboard?slate=` | One slate's standings (the whole week if omitted) |
| `POST /api/play/{id}/share` | Mint a read-only link token for a finished slip |
| `GET /api/play/shared/{shareId}` | A shared slip, without its entry id or respins |
| `POST /api/account/guest` | Start a guest session (no-op if there is already one) |
| `GET /api/account/auth-config` | The Supabase project URL and publishable key the page signs in with; empty if unset |
| `POST /api/account/supabase` | JSON `{accessToken}` from Supabase; sets an HttpOnly session cookie. From a guest session, keeps the guest's rosters |
| `POST /api/account/name-check` | JSON `{name}`; says whether a name is free, before an email sign-up |
| `POST /api/account/signout` | Ends this browser's session |
| `GET /api/account/me` | Who is signed in |
| `POST /api/account/name` | Change your name, on every roster too |
| `GET /api/account/rosters` | Every roster on your profile, with status, score and rank |
| `GET /api/boards` | Every leaderboard worth listing |
| `GET /api/boards/one?contestId=&slate=` | One board, best roster per profile |
| `GET /api/scores` | This week's games with scores and clocks (cached 15s) |
| `GET /api/scores/{eventId}` | One game's passing, rushing and receiving leaders |

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

**A roster belongs to a profile, not to whoever holds its id.** The id used to be the whole
credential: every pick endpoint accepted it alone, and at one point the leaderboard published one
per player. Rosters now carry the profile that made them, and every read or write checks it, so
an id in the address bar or a screenshot opens nothing for anyone else. Rosters played before
profiles have no owner and are handed to the first signed-in browser that holds their id.
Shared slips use a separate read-only token.

**A made pick is final.** Only the pick being played can be spun or chosen. Without that, a
past week's roster could be completed, its results read, and a bad pick respun or reassigned
to climb the board.

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

- **Password reset.** Needs custom SMTP in Supabase first (Resend or similar); the built-in
  mailer cannot reach players. Until then a forgotten password means signing in with Google.
- **Decide whether the lopsided choice still needs a fix.** Standard scoring means the highest
  expected option is nearly always the same one. The uniqueness rule is now in (each part goes
  once per position), which turns the question into which player gets the good part. Worth
  playing a few weeks before deciding whether anything further is needed.
- **The replayer**: stream a finished game through the pipeline at speed, so scoring can be
  developed out of season and the determinism claim can actually be tested.

Open design question: the QB slot's team spin is mostly theatre. Fifteen of thirty-two teams
have exactly one eligible quarterback, so the spin resolves to a forced pick. Probably the
QB slot should wheel straight to a player and skip the team stage, which also buys back
interactions against the sixty-second budget. RB averages 2.8 options per team and flex 5.3,
so both keep the two-stage flow.
