package dev.mikeyku.wheelhouse.ingest;

import dev.mikeyku.wheelhouse.contest.Contest;
import dev.mikeyku.wheelhouse.contest.ContestService;
import dev.mikeyku.wheelhouse.espn.BoxscoreParser;
import dev.mikeyku.wheelhouse.espn.EspnClient;
import dev.mikeyku.wheelhouse.sleeper.AthleteResolver;
import dev.mikeyku.wheelhouse.sleeper.PlayerCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Teaches the resolver who everyone is from the weeks of this season already played.
 *
 * <p>The form guide reads a player's game log by ESPN id, and for anyone who arrived in 2021 or
 * later the only source of that id is having been seen in a box score by this process. On a
 * fresh start that is nobody, so every player in the live week read "no history, has not taken
 * a snap before this week", including starters in their third game. The archive never had the
 * problem because loading a week parses its box scores; the live week only parses games as
 * they are played.
 *
 * <p>Only the resolver learns from these. The stats are not ingested: nothing scores against a
 * finished week of the current season, and holding them would cost memory for nothing.
 */
@Component
public class SeasonBackfill {

    private static final Logger log = LoggerFactory.getLogger(SeasonBackfill.class);

    private final EspnClient espn;
    private final BoxscoreParser parser;
    private final AthleteResolver resolver;
    private final ContestService contests;
    private final PlayerCatalog catalog;
    /** Recent weeks are enough: a player who has not appeared in four weeks has no form to read. */
    private final int weeksBack;

    private volatile String doneFor;

    public SeasonBackfill(EspnClient espn, BoxscoreParser parser, AthleteResolver resolver,
                          ContestService contests, PlayerCatalog catalog,
                          @Value("${wheelhouse.backfill.weeks:4}") int weeksBack) {
        this.espn = espn;
        this.parser = parser;
        this.resolver = resolver;
        this.contests = contests;
        this.catalog = catalog;
        this.weeksBack = weeksBack;
    }

    /**
     * Runs once per live week, and again after a restart. Waits for the player catalog, since
     * matching a box score against an empty catalog learns nothing and would still count as done.
     */
    @Scheduled(initialDelay = 10_000, fixedDelayString = "${wheelhouse.backfill.check-ms:600000}")
    public void backfill() {
        Contest week = contests.known();
        if (week == null || week.archive() || week.seasonType() != 2 || week.week() <= 1
                || catalog.size() == 0 || week.id().equals(doneFor)) {
            return;
        }
        int before = resolver.resolvedCount();
        int games = 0;
        for (int w = Math.max(1, week.week() - weeksBack); w < week.week(); w++) {
            try {
                for (EspnClient.GameRef game : espn.scoreboard(week.season(), 2, w)) {
                    resolver.learnFrom(parser.parse("backfill", game.eventId(),
                            espn.summary(game.eventId()), Instant.now()));
                    games++;
                }
            } catch (Exception e) {
                // Leave doneFor unset so the next check tries again.
                log.warn("backfill of {} week {} failed: {}", week.season(), w, e.toString());
                return;
            }
        }
        doneFor = week.id();
        log.info("backfill: {} games from earlier weeks, {} athletes newly resolved ({} known)",
                games, resolver.resolvedCount() - before, resolver.resolvedCount());
    }
}
