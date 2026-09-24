package dev.mikeyku.wheelhouse.contest;

import dev.mikeyku.wheelhouse.espn.BoxscoreParser;
import dev.mikeyku.wheelhouse.espn.EspnClient;
import dev.mikeyku.wheelhouse.ingest.IngestService;
import dev.mikeyku.wheelhouse.model.GameSnapshot;
import dev.mikeyku.wheelhouse.projection.PositionalField;
import dev.mikeyku.wheelhouse.projection.ProjectionService;
import dev.mikeyku.wheelhouse.sleeper.AthleteResolver;
import dev.mikeyku.wheelhouse.sleeper.PlayerCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Loads a finished week from ESPN so it can be played long after the fact.
 *
 * <p>Nothing here parses anything new. A 2007 box score has the same shape as a live one, so
 * this is just the live ingestion path pointed at a date, and every stat, every score and
 * every body part works unchanged.
 *
 * <p>Loading is once-only per week while the week stays in memory. Historical results do not
 * change, so there is nothing left to learn from ESPN about a loaded week; but memory is finite,
 * so only the most recently used weeks stay loaded and the rest are released and re-read on
 * demand.
 */
@Service
public class ArchiveService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveService.class);

    private final EspnClient espn;
    private final BoxscoreParser parser;
    private final IngestService ingest;
    private final AthleteResolver resolver;
    private final ContestService contests;
    private final ProjectionService projections;
    private final ArchiveRoster roster;
    private final PlayerCatalog catalog;
    private final PositionalField field;
    private final int seasonsBack;

    private final LoadedContests loaded;

    public ArchiveService(EspnClient espn, BoxscoreParser parser, IngestService ingest,
                          AthleteResolver resolver, ContestService contests,
                          ProjectionService projections, ArchiveRoster roster,
                          PlayerCatalog catalog, PositionalField field,
                          @Value("${wheelhouse.archive.seasons:5}") int seasonsBack,
                          @Value("${wheelhouse.archive.max-loaded:8}") int maxLoaded) {
        this.espn = espn;
        this.parser = parser;
        this.ingest = ingest;
        this.resolver = resolver;
        this.contests = contests;
        this.projections = projections;
        this.roster = roster;
        this.catalog = catalog;
        this.field = field;
        this.seasonsBack = seasonsBack;
        this.loaded = new LoadedContests(maxLoaded, this::release);
    }

    /**
     * Lets go of everything a week put in memory, in every service that holds a piece of it.
     * Each piece is keyed by contest, so this is the same prefix removal five times over, and
     * the next load of the week starts from nothing rather than from a half-present state.
     */
    void release(String contestId) {
        ingest.evict(contestId);
        roster.evict(contestId);
        catalog.evictArchived(contestId);
        projections.evict(contestId);
        field.evict(contestId);
        log.info("archive released {}", contestId);
    }

    /** The most recent completed season. The current one is still being played. */
    public int latestSeason() {
        return contests.current().season() - 1;
    }

    /**
     * ESPN serves box scores back to about 2001, but the archive is deliberately narrower.
     * The game is played against projections, and Sleeper only publishes those from 2019, so a
     * season without them is unplayable rather than merely old.
     */
    public int earliestSeason() {
        return latestSeason() - (seasonsBack - 1);
    }

    public Contest load(int season, int week) {
        if (season < earliestSeason() || season > latestSeason()) {
            throw new IllegalArgumentException(
                    "Past weeks go from " + earliestSeason() + " to " + latestSeason() + ".");
        }
        Contest contest = Contest.archived(season, week);
        // Projections are part of being loaded. Without them in the latch, the second visit to
        // a week short-circuits before they are ever fetched and the whole wheel reads zero.
        // "Known" rather than "available": a week Sleeper genuinely has no forecasts for is
        // still fully loaded, and must not be re-read from ESPN on every visit.
        if (loaded.contains(contest.id())
                && ingest.hasContest(contest.id())
                && projections.known(contest.id())) {
            loaded.touch(contest.id());
            roster.players(contest.id());
            return contest;
        }
        // Claim the slot before fetching, so the stalest week is released before this one
        // allocates rather than after. On a small heap that order is the whole point.
        loaded.touch(contest.id());
        projections.load(contest);

        try {
            List<EspnClient.GameRef> games = espn.scoreboard(season, 2, week);
            if (games.isEmpty()) {
                throw new IllegalArgumentException(
                        "No games in " + season + " week " + week + ".");
            }
            int stats = 0;
            for (EspnClient.GameRef game : games) {
                GameSnapshot snapshot = parser.parse(
                        contest.id(), game.eventId(), espn.summary(game.eventId()), Instant.now());
                resolver.learnFrom(snapshot);
                ingest.ingest(snapshot);
                stats += snapshot.stats().size();
            }
            // Derive the week's player pool here rather than waiting for the first spin. The
            // pool is also what registers archived players in the catalog, and an entry resumed
            // from disk needs them back before it can name anyone or score anything.
            roster.players(contest.id());
            log.info("archive loaded {} : {} games, {} stats ({} weeks in memory)",
                    contest.label(), games.size(), stats, loaded.size());
            return contest;
        } catch (IllegalArgumentException e) {
            loaded.evict(contest.id());
            throw e;
        } catch (Exception e) {
            loaded.evict(contest.id());
            // The cause goes to the log, not the page: it is an ESPN URL and a status code.
            log.warn("could not load {} week {}: {}", season, week, e.toString());
            throw new IllegalStateException("Couldn't load " + season + " week " + week
                    + ". Try again in a minute.");
        }
    }

    public boolean isLoaded(String contestId) {
        return loaded.contains(contestId);
    }

    /** The archived weeks currently in memory, stalest first. */
    public List<String> loadedContests() {
        return loaded.ids();
    }
}
