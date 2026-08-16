package dev.mikeyku.wheelhouse.ingest;

import dev.mikeyku.wheelhouse.contest.ContestService;
import dev.mikeyku.wheelhouse.espn.BoxscoreParser;
import dev.mikeyku.wheelhouse.espn.EspnClient;
import dev.mikeyku.wheelhouse.model.GameSnapshot;
import dev.mikeyku.wheelhouse.model.StatDelta;
import dev.mikeyku.wheelhouse.sleeper.AthleteResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers games and pulls box scores for the ones worth pulling.
 *
 * <p>Two intervals rather than one: the scoreboard changes slowly and is cheap, box scores
 * change constantly. Games that have not kicked off are never polled, so an ordinary Tuesday
 * costs two requests a minute.
 *
 * <p>Finished games are polled exactly once. Their final box score is the authoritative
 * number a roster scores against, and it has to be captured or a game that ends while
 * nobody is watching scores zero forever. Once captured, there is nothing left to learn
 * from that game, so it stops being polled.
 */
@Component
public class GamePoller {

    private static final Logger log = LoggerFactory.getLogger(GamePoller.class);

    private final EspnClient espn;
    private final BoxscoreParser parser;
    private final IngestService ingest;
    private final AthleteResolver resolver;
    private final ContestService contests;

    private volatile List<EspnClient.GameRef> games = List.of();
    private final Set<String> finalised = ConcurrentHashMap.newKeySet();

    public GamePoller(EspnClient espn, BoxscoreParser parser, IngestService ingest,
                      AthleteResolver resolver, ContestService contests) {
        this.espn = espn;
        this.parser = parser;
        this.ingest = ingest;
        this.resolver = resolver;
        this.contests = contests;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${wheelhouse.poll.scoreboard-ms:30000}")
    public void refreshGames() {
        try {
            games = espn.scoreboard();
            log.info("scoreboard: {} games, {} live, {} finals captured",
                    games.size(), games.stream().filter(EspnClient.GameRef::isLive).count(), finalised.size());
        } catch (Exception e) {
            log.warn("scoreboard poll failed: {}", e.toString());
        }
    }

    @Scheduled(initialDelay = 2000, fixedDelayString = "${wheelhouse.poll.boxscore-ms:15000}")
    public void pollBoxscores() {
        for (EspnClient.GameRef game : games) {
            if (!shouldPoll(game)) {
                continue;
            }
            try {
                GameSnapshot snapshot = parser.parse(contests.current().id(),
                        game.eventId(), espn.summary(game.eventId()), Instant.now());
                resolver.learnFrom(snapshot);
                List<StatDelta> deltas = ingest.ingest(snapshot);

                if (snapshot.isFinal()) {
                    finalised.add(game.eventId());
                    log.info("final captured: {} ({} stats)", game.name(), snapshot.stats().size());
                } else if (!deltas.isEmpty()) {
                    log.info("{} [{}] {} deltas", game.name(), snapshot.detail(), deltas.size());
                }
            } catch (Exception e) {
                log.warn("boxscore poll failed for {}: {}", game.eventId(), e.toString());
            }
        }
    }

    /** Live games always, finished games once, games that have not started never. */
    private boolean shouldPoll(EspnClient.GameRef game) {
        if (game.isLive()) {
            return true;
        }
        return "post".equals(game.state()) && !finalised.contains(game.eventId());
    }

    public List<EspnClient.GameRef> currentLiveGames() {
        return games.stream().filter(EspnClient.GameRef::isLive).toList();
    }

    public List<EspnClient.GameRef> allGames() {
        return games;
    }
}
