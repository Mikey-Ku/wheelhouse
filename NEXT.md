# Next

Two tracks. The first is getting it in front of people, the second is getting it in front
of the right people. The second only matters if the first is done, because nobody at an
operator will clone a repo to try your game.

---

## Track 1 — Deploy

### What is already true

- `/actuator/health` responds, so any host can probe it
- Entries persist to a file-backed database
- The blind draft is enforced server-side, and no endpoint hands out another player's roster
- The repo is public and a fresh clone compiles

### What is missing

| | Why |
|---|---|
| Postgres driver | Render's disk is ephemeral. A file database is wiped on every deploy |
| `server.port=${PORT:8080}` | Render assigns a port and the app currently ignores it |
| Dockerfile | Pins Java 21 rather than letting the build guess |

### Steps

**1. Prep the app.** Postgres driver alongside H2, datasource read from the environment
with the local file database as the default, port binding, Dockerfile, deploy notes.
Nothing here needs an account, so it can happen before you have one.

**2. Create a Supabase project.** Free tier. Settings → Database → connection string.

**3. Create a Render web service.** Free tier, connect this repo. It detects Maven.

**4. Paste the connection string into Render's environment variables yourself.**
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.
A database credential has no reason to pass through a chat log.

**5. Play it on the real URL, then send it to four people who like football.**

### Known cost of the free tier

Render spins a free web service down after 15 minutes idle. The first visitor after a
quiet spell waits 30 to 60 seconds, and the ESPN poller stops while it sleeps. That is
tolerable for testing and bad for a link you want people to click. $7/month on Render
removes it, and a $5 VPS does the same with more of an infrastructure story to tell.

Deploy free first. Live beats optimised.

### After it is live

- **Accounts.** Done: Supabase Auth with Google and email. Discord is one more provider in the
  Supabase dashboard and needs no code.
- **A week grid.** Ninety weeks across five seasons, showing which you have played and
  what you captured on each. This is the retention mechanic; it needs accounts first.

---

## Track 2 — Getting an operator's attention

The pitch is not "here is a game". Operators have games. The pitch is a mechanic plus a
measurement, and the measurement is the part nobody else brings.

### What is actually interesting to them

**A completed roster is a parlay slip, and the game removes the research.** The reason
people do not build multi-leg slips is that choosing fourteen legs is work. Here the
wheel chooses, and the player only decides which stat. That is a slip generator with a
front end people will actually use.

**The measurement.** Across 17,552 player-weeks of archived football, actuals clear
their projection 49.5% of the time in aggregate. Per stat it is nothing like that:

| stat | clears | fair price | what a naive book charges |
|---|---|---|---|
| receptions | 54.0% | −117 | −110 |
| receiving yards | 48.4% | +107 | −110 |
| passing yards | 46.0% | +117 | −110 |
| passing TDs | 37.3% | +168 | −110 |
| **rushing TDs** | **17.1%** | **+485** | −110 |

Every composite position is forced to contain a touchdown part. Priced naively at −110 a
leg, a quarterback slip holds **82%** rather than the 17% a coin-flip assumption predicts.

**The honest version of that is the pitch.** An 82% hold is not a product, it is a
mispricing: charging −110 for a +485 event. Say so. Walking in with "price the touchdown
legs correctly and here is what the vig actually is" is the version a quant respects, and
"we found free money" is the version that ends the meeting.

### Order of operations

1. **Deploy it.** Nothing below works without a URL somebody can click.
2. **Play it with friends for a few weeks.** Real usage answers questions no amount of
   analysis will: is 14 picks too long, does the capture rate make people replay, does
   anybody care about the vault.
3. **Write the finding up properly.** Two pages: the mechanic, the per-stat hit rates,
   the correlation question, and what you would need to price it safely. That document is
   the thing worth sending, not the repo.
4. **Send it to a person, not a careers page.** Sleeper's engineers and product people are
   findable on X and LinkedIn. So are Underdog's and PrizePicks'. A short message with a
   live link and one surprising number beats an application form.
5. **Have the boring answers ready.** Free-to-play is legally clean because it fails both
   consideration and prize. You are pitching a mechanic to somebody who already holds the
   licences, not proposing to operate anything. Headshots are the real exposure and you
   would drop them on request.

### What would strengthen it most, in order

1. **Coverage backtesting.** The pricing model has never been checked against outcomes.
   Does the 90% interval contain the actual 90% of the time? That is one number and it
   turns "I built a Monte Carlo" into "I built one and here is the evidence it works".
2. **The correlation measurement.** The copula exists to handle correlated legs and
   nothing has yet measured the correlation on real slips. It is the difference between a
   57% hold and a negative one.
3. **Real historical odds.** About $59 for 2023 to 2025. Replaces the assumption that
   props sit at −110 with what books actually charged. Worth buying only after 1 and 2,
   because it refines a model rather than validating one.

---

## Smaller things

- The QB team spin is nearly theatre: half the league carries one eligible starter. The
  slot could wheel straight to a player.
- Hard mode exists in `Slot` (interceptions and fumbles lost as parts that only cost you)
  and is not wired to anything.
- **A finished live week does not survive a restart.** Its box scores live in memory and only
  the current week is polled, so once ESPN rolls to the next week a restart leaves last week's
  rosters scoring zero. An archived week rehydrates itself on read; a finished week of the
  current season should do the same.
- **The classic wheel can land on a backup quarterback.** The relevance cutoff lets about
  twenty backups through. The showdown already filters on projected points instead, and the
  same rule would work for Sunday.
- **Thursday Night is the first real test of slates under live scoring.** Watch `/live.html`
  and `/ops.html` during ATL @ GB: a pick should move from pending to a live clock to final.
