package dev.mikeyku.wheelhouse.model;

/**
 * One NFL player, as Sleeper knows them.
 *
 * <p>{@code id} is Sleeper's key and ours. {@code espnId} is what joins to the live box
 * score feed, and Sleeper carries it on the player record, so no separate crosswalk is
 * needed.
 *
 * <p>{@code searchRank} is Sleeper's relevance ordering. Anyone nobody has heard of sits at
 * 9999999, which is what keeps practice squad linemen off the wheel.
 */
public record Player(
        String id,
        String espnId,
        String name,
        String searchName,
        String team,
        String position,
        int searchRank,
        String injuryStatus) {

    public boolean rostered() {
        return team != null && !team.isBlank();
    }

    /**
     * Collapses a display name to a comparison key: lowercase, alphanumeric only, generational
     * suffix removed. Sleeper ships this form as {@code search_full_name}, and applying the
     * same rule to ESPN's names lets the two be matched.
     *
     * <p>"Amon-Ra St. Brown" and "Marvin Harrison Jr." both survive it intact as
     * {@code amonrastbrown} and {@code marvinharrison}.
     */
    public static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String cleaned = name.toLowerCase()
                .replaceAll("\\b(jr|sr|ii|iii|iv|v)\\b", "")
                .replaceAll("[^a-z0-9]", "");
        return cleaned.isEmpty() ? null : cleaned;
    }
}
