package dev.mikeyku.wheelhouse.contest;

import java.time.Instant;

/**
 * One week of play, either the live one or a finished one from the archive.
 *
 * <p>Live weeks are read off ESPN's scoreboard, so preseason, regular season and playoffs all
 * fall out correctly without a hardcoded calendar, and the lock is the first kickoff.
 *
 * <p>Archived weeks are already over, so they never lock. They also cannot be a fair
 * competition, since the results are a search away. They are a sandbox: play week 8 of 2007
 * for the nostalgia, and test the game out of season without waiting for Sunday.
 */
public record Contest(int season, int seasonType, int week, Instant lockAt, boolean archive) {

    public static Contest live(int season, int seasonType, int week, Instant lockAt) {
        return new Contest(season, seasonType, week, lockAt, false);
    }

    public static Contest archived(int season, int week) {
        return new Contest(season, 2, week, null, true);
    }

    public String id() {
        return "%s%d-%d-%d".formatted(archive ? "a" : "", season, seasonType, week);
    }

    public String label() {
        if (archive) {
            return season + " week " + week;
        }
        return switch (seasonType) {
            case 1 -> "Preseason week " + week;
            case 3 -> "Playoffs round " + week;
            default -> "Week " + week;
        };
    }

    public boolean locked(Instant now) {
        return !archive && lockAt != null && !now.isBefore(lockAt);
    }
}
