package dev.mikeyku.wheelhouse.model;

import java.time.Instant;

/**
 * A change in one stat between two snapshots. This is the event that eventually
 * lands in the append-only log and drives scoring.
 *
 * <p>{@code previous} is null when the stat is seen for the first time.
 */
public record StatDelta(
        String eventId,
        StatKey key,
        Double previous,
        double current,
        Instant observedAt) {

    public double change() {
        return previous == null ? current : current - previous;
    }
}
