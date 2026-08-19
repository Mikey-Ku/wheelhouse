package dev.mikeyku.wheelhouse.contest;

import dev.mikeyku.wheelhouse.espn.BoxscoreParser;
import dev.mikeyku.wheelhouse.espn.EspnClient;
import dev.mikeyku.wheelhouse.ingest.IngestService;
import dev.mikeyku.wheelhouse.model.GameSnapshot;
import dev.mikeyku.wheelhouse.projection.ProjectionService;
import dev.mikeyku.wheelhouse.sleeper.AthleteResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads a finished week from ESPN so it can be played long after the fact.
 *
 * <p>Nothing here parses anything new. A 2007 box score has the same shape as a live one, so
 * this is just the live ingestion path pointed at a date, and every stat, every score and
 * every body part works unchanged.
 *
 * <p>Loading is once-only per week. Historical results do not change, so once a week is in
 * memory there is nothing left to learn from ESPN about it.
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
    private final int seasonsBack;

    private final Set<String> loaded = ConcurrentHashMap.newKeySet();

    public ArchiveService(EspnClient espn, BoxscoreParser parser, IngestService ingest,
                          AthleteResolver resolver, ContestService contests,
                          ProjectionService projections, ArchiveRoster roster,
                          @Value("${wheelhouse.archive.seasons:5}") int seasonsBack) {
        this.espn = espn;
        this.parser = parser;
        this.ingest = ingest;
        this.resolver = resolver;
        this.contests = contests;
        this.projections = projections;
        this.roster = roster;
        this.seasonsBack = seasonsBack;
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
                    "archive covers " + earliestSeason() + " to " + latestSeason());
        }
        Contest contest = Contest.archived(season, week);
        // Projections are part of being loaded. Without them in the latch, the second visit to
        // a week short-circuits before they are ever fetched and the whole wheel reads zero.
        if (loaded.contains(contest.id())
                && ingest.hasContest(contest.id())
                && projections.available(contest.id())) {
            roster.players(contest.id());
            return contest;
        }
        projections.load(contest);

        try {
            List<EspnClient.GameRef> games = espn.scoreboard(season, 2, week);
            if (games.isEmpty()) {
                throw new IllegalArgumentException(
                        "no games found for " + season + " week " + week);
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
            loaded.add(contest.id());
            log.info("archive loaded {} : {} games, {} stats", contest.label(), games.size(), stats);
            return contest;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("could not load " + season + " week " + week + ": "
                    + e.getMessage());
        }
    }

    public boolean isLoaded(String contestId) {
        return loaded.contains(contestId);
    }
}
