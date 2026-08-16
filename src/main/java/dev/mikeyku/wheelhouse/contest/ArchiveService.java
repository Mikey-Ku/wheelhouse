package dev.mikeyku.wheelhouse.contest;

import dev.mikeyku.wheelhouse.espn.BoxscoreParser;
import dev.mikeyku.wheelhouse.espn.EspnClient;
import dev.mikeyku.wheelhouse.ingest.IngestService;
import dev.mikeyku.wheelhouse.model.GameSnapshot;
import dev.mikeyku.wheelhouse.sleeper.AthleteResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /** ESPN has no usable box scores before this. */
    public static final int EARLIEST_SEASON = 2001;

    private final EspnClient espn;
    private final BoxscoreParser parser;
    private final IngestService ingest;
    private final AthleteResolver resolver;

    private final Set<String> loaded = ConcurrentHashMap.newKeySet();

    public ArchiveService(EspnClient espn, BoxscoreParser parser, IngestService ingest,
                          AthleteResolver resolver) {
        this.espn = espn;
        this.parser = parser;
        this.ingest = ingest;
        this.resolver = resolver;
    }

    public Contest load(int season, int week) {
        if (season < EARLIEST_SEASON) {
            throw new IllegalArgumentException("no box scores before " + EARLIEST_SEASON);
        }
        Contest contest = Contest.archived(season, week);
        if (loaded.contains(contest.id()) && ingest.hasContest(contest.id())) {
            return contest;
        }

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
