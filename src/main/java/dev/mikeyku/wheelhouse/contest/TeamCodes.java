package dev.mikeyku.wheelhouse.contest;

/**
 * ESPN and Sleeper agree on every team code but one: ESPN writes Washington as WSH, Sleeper as
 * WAS. The wheel speaks Sleeper, so anything read off an ESPN scoreboard is translated here
 * before it is compared with a player's team.
 */
public final class TeamCodes {

    private TeamCodes() {
    }

    public static String fromEspn(String code) {
        if (code == null) {
            return null;
        }
        return "WSH".equalsIgnoreCase(code) ? "WAS" : code.toUpperCase();
    }
}
