package dev.mikeyku.wheelhouse.contest;

import dev.mikeyku.wheelhouse.espn.EspnClient;
import dev.mikeyku.wheelhouse.projection.ProjectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Knows which week it currently is and when that week locks.
 *
 * <p>Both come from ESPN rather than a calendar we maintain, which means the schedule
 * shifting, a flexed game, or the jump from preseason to regular season all take care of
 * themselves.
 */
@Service
public class ContestService {

    private static final Logger log = LoggerFactory.getLogger(ContestService.class);

    private final EspnClient espn;
    private final ProjectionService projections;
    private volatile Contest current;

    public ContestService(EspnClient espn, ProjectionService projections) {
        this.espn = espn;
        this.projections = projections;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${wheelhouse.contest.refresh-ms:300000}")
    public void refresh() {
        try {
            JsonNode board = espn.scoreboardRaw();
            int season = board.path("season").path("year").asInt();
            int seasonType = board.path("season").path("type").asInt(2);
            int week = board.path("week").path("number").asInt();

            Instant firstKickoff = null;
            for (JsonNode event : board.path("events")) {
                Instant date = parse(event.path("date").asText(null));
                if (date != null && (firstKickoff == null || date.isBefore(firstKickoff))) {
                    firstKickoff = date;
                }
            }

            Contest refreshed = Contest.live(season, seasonType, week, firstKickoff);
            if (!refreshed.equals(current)) {
                log.info("contest is now {} ({}), locks {}",
                        refreshed.label(), refreshed.id(), refreshed.lockAt());
            }
            current = refreshed;
            projections.load(refreshed);
        } catch (Exception e) {
            log.warn("contest refresh failed: {}", e.toString());
        }
    }

    public Contest current() {
        if (current == null) {
            refresh();
        }
        return current;
    }

    /**
     * Rebuilds a contest from its id, so an entry saved weeks ago still knows what it belongs
     * to. Archive ids carry a leading "a".
     */
    public Contest byId(String contestId) {
        if (contestId == null) {
            return current();
        }
        Contest now = current();
        if (now != null && contestId.equals(now.id())) {
            return now;
        }
        boolean archive = contestId.startsWith("a");
        String[] parts = (archive ? contestId.substring(1) : contestId).split("-");
        if (parts.length != 3) {
            return now;
        }
        try {
            int season = Integer.parseInt(parts[0]);
            int seasonType = Integer.parseInt(parts[1]);
            int week = Integer.parseInt(parts[2]);
            return archive
                    ? Contest.archived(season, week)
                    : Contest.live(season, seasonType, week, null);
        } catch (NumberFormatException e) {
            return now;
        }
    }

    /**
     * ESPN omits seconds, sending {@code 2026-08-13T23:00Z}, which {@code Instant.parse}
     * rejects outright. ISO_OFFSET_DATE_TIME treats seconds as optional and handles both.
     */
    private Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            log.warn("could not parse kickoff time '{}'", value);
            return null;
        }
    }
}
