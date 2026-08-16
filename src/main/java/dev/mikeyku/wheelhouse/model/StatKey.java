package dev.mikeyku.wheelhouse.model;

/**
 * Identifies one measurable number: a specific stat, for a specific athlete,
 * within a specific ESPN box score category.
 *
 * <p>athleteId is ESPN's id. It joins to Sleeper's player table via {@code espn_id}.
 */
public record StatKey(String athleteId, String category, String stat) {

    @Override
    public String toString() {
        return athleteId + ":" + category + "." + stat;
    }
}
