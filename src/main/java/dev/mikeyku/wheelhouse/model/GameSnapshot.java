package dev.mikeyku.wheelhouse.model;

import java.time.Instant;
import java.util.Map;

/**
 * A complete, point-in-time reading of every stat in one game.
 *
 * <p>ESPN serves cumulative totals rather than an event stream, so a snapshot is the
 * unit we fetch. Deltas are derived by diffing consecutive snapshots, which makes
 * ingestion self-healing: a missed poll is caught up by the next one, and a duplicate
 * poll produces an empty diff.
 */
public record GameSnapshot(
        String contestId,
        String eventId,
        String name,
        String state,
        String detail,
        Instant fetchedAt,
        Map<StatKey, Double> stats,
        Map<String, String> athleteNames,
        Map<String, String> athleteTeams) {

    /** Snapshots are unique per week, not per game: the same event id never spans contests. */
    public String key() {
        return contestId + "|" + eventId;
    }

    public boolean isLive() {
        return "in".equals(state);
    }

    public boolean isFinal() {
        return "post".equals(state);
    }
}
