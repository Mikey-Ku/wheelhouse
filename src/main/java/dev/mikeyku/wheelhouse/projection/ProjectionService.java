package dev.mikeyku.wheelhouse.projection;

import dev.mikeyku.wheelhouse.contest.Contest;
import dev.mikeyku.wheelhouse.model.Player;
import dev.mikeyku.wheelhouse.model.Slot;
import dev.mikeyku.wheelhouse.sleeper.SleeperClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each player was expected to do in a given week.
 *
 * <p>This is the number the game is actually played against. You choose which part of a
 * player to take while knowing only the forecast, exactly as you would before kickoff, and
 * the real result arrives later.
 *
 * <p>Deliberately not routed through {@code StatKey} or {@code AthleteResolver}. Those are
 * built on ESPN athlete ids, which are only learned once a player has appeared in a box
 * score, so before kickoff nobody would resolve and the whole wheel would read zero.
 * Projections carry Sleeper's own ids and Sleeper's own stat names, and are indexed here on
 * their own terms.
 *
 * <p>Two indexes, because neither identifier alone is sufficient. Sleeper's player id joins
 * perfectly for live weeks, but archived weeks mint synthetic ids from box scores and cannot
 * use it. Normalised name plus team covers those, and is collision-free within a single
 * week's rows.
 */
@Service
public class ProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionService.class);

    /** Fields present on every row whether or not a real projection exists. */
    private static final Set<String> FILLER = Set.of("adp_dd_ppr", "pos_adp_dd_ppr");

    private final SleeperClient sleeper;

    /** contestId to (lookup key to (sleeper stat name to value)). */
    private final Map<String, Week> byContest = new ConcurrentHashMap<>();

    public ProjectionService(SleeperClient sleeper) {
        this.sleeper = sleeper;
    }

    private record Week(Map<String, Map<String, Double>> bySleeperId,
                        Map<String, Map<String, Double>> byNameTeam,
                        int players) {
        boolean usable() {
            return players > 0;
        }
    }

    /** Idempotent. Historic projections never change, and a live week is cached on disk. */
    public void load(Contest contest) {
        byContest.computeIfAbsent(contest.id(), id -> fetch(contest));
    }

    /**
     * Whether this week has projections at all. Preseason and postseason do not, so a contest
     * played in those windows cannot be scored against a forecast and the UI has to say so
     * rather than quietly showing zeroes.
     */
    public boolean available(String contestId) {
        Week week = byContest.get(contestId);
        return week != null && week.usable();
    }

    public int size(String contestId) {
        Week week = byContest.get(contestId);
        return week == null ? 0 : week.players();
    }

    /** Projected value of one stat for one player, or null when there is no forecast. */
    public Double projected(String contestId, Player player, Slot.StatOption option) {
        Week week = byContest.get(contestId);
        if (week == null || player == null) {
            return null;
        }
        Map<String, Double> stats = week.bySleeperId().get(player.id());
        if (stats == null) {
            stats = week.byNameTeam().get(nameTeamKey(player.searchName(), player.team()));
        }
        if (stats == null) {
            return null;
        }
        // Sleeper omits a key entirely rather than sending zero, and an omitted stat genuinely
        // means "not expected to record any", so absent reads as zero rather than unknown.
        return stats.getOrDefault(option.projectionStat(), 0.0);
    }

    private Week fetch(Contest contest) {
        try {
            JsonNode rows = sleeper.projections(contest.season(), contest.seasonType(), contest.week());
            Map<String, Map<String, Double>> bySleeperId = new HashMap<>();
            Map<String, Map<String, Double>> byNameTeam = new HashMap<>();

            for (JsonNode row : rows) {
                JsonNode stats = row.path("stats");
                Map<String, Double> values = new HashMap<>();
                for (String field : stats.propertyNames()) {
                    if (!FILLER.contains(field)) {
                        values.put(field, stats.path(field).asDouble(0));
                    }
                }
                if (values.isEmpty()) {
                    continue;
                }

                String sleeperId = row.path("player_id").asText(null);
                if (sleeperId != null) {
                    bySleeperId.put(sleeperId, values);
                }

                // The top-level team is who they played for in THAT game. The nested
                // player.team is their current roster and is wrong for every historic row:
                // Aaron Rodgers' 2021 Green Bay projections carry a nested team of PIT.
                JsonNode p = row.path("player");
                String name = (p.path("first_name").asText("") + " " + p.path("last_name").asText("")).trim();
                String key = nameTeamKey(Player.normalize(name), row.path("team").asText(null));
                if (key != null) {
                    byNameTeam.put(key, values);
                }
            }

            Week week = new Week(Map.copyOf(bySleeperId), Map.copyOf(byNameTeam), bySleeperId.size());
            log.info("projections for {}: {} players with a forecast", contest.label(), week.players());
            return week;
        } catch (Exception e) {
            log.warn("projection load failed for {}: {}", contest.label(), e.toString());
            return new Week(Map.of(), Map.of(), 0);
        }
    }

    private static String nameTeamKey(String searchName, String team) {
        if (searchName == null || team == null || team.isBlank()) {
            return null;
        }
        String t = team.equalsIgnoreCase("WSH") ? "WAS" : team.toUpperCase();
        return searchName + "|" + t;
    }
}
