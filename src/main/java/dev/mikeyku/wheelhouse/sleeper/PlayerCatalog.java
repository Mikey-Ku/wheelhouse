package dev.mikeyku.wheelhouse.sleeper;

import dev.mikeyku.wheelhouse.model.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The player table, loaded from Sleeper and held in memory.
 *
 * <p>Small enough to keep whole (a few thousand rows) and read constantly by the wheel, so
 * there is no reason for it to live anywhere else.
 */
@Service
public class PlayerCatalog {

    private static final Logger log = LoggerFactory.getLogger(PlayerCatalog.class);

    private final SleeperClient sleeper;

    private volatile Map<String, Player> byId = Map.of();
    private volatile Map<String, Player> byEspnId = Map.of();
    private volatile Map<String, Player> byNameTeam = Map.of();

    /**
     * Sleeper id to ESPN id for every player Sleeper has ever heard of, active or not.
     *
     * <p>The playable catalog above is filtered to active players, which is right for the
     * wheel and wrong for everything else. Archived weeks are full of players who have since
     * retired, and projections for those weeks still have to join to their box score lines,
     * so resolution needs the unfiltered map.
     */
    private volatile Map<String, String> espnIdBySleeperId = Map.of();

    public PlayerCatalog(SleeperClient sleeper) {
        this.sleeper = sleeper;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${wheelhouse.sleeper.refresh-ms:43200000}")
    public void refresh() {
        try {
            JsonNode root = sleeper.players();
            Map<String, Player> loaded = new HashMap<>();
            Map<String, Player> byEspn = new HashMap<>();
            Map<String, Player> byNameTeam = new HashMap<>();
            Map<String, String> espnIds = new HashMap<>();

            for (String id : root.propertyNames()) {
                JsonNode n = root.path(id);

                // Resolution covers everyone; only the playable catalog is filtered to active.
                String anyEspnId = text(n, "espn_id");
                if (anyEspnId != null) {
                    espnIds.put(id, anyEspnId);
                }
                if (!n.path("active").asBoolean(false)) {
                    continue;
                }
                String fullName = n.path("full_name").asText(null);
                String searchName = text(n, "search_full_name");
                Player player = new Player(
                        id,
                        text(n, "espn_id"),
                        fullName,
                        searchName != null ? searchName : Player.normalize(fullName),
                        text(n, "team"),
                        text(n, "position"),
                        n.path("search_rank").asInt(Integer.MAX_VALUE),
                        text(n, "injury_status"));

                loaded.put(id, player);
                if (player.espnId() != null) {
                    byEspn.put(player.espnId(), player);
                }
                if (player.searchName() != null && player.rostered()) {
                    // Name plus team is unique in practice; last write wins on the rare clash.
                    byNameTeam.put(nameTeamKey(player.searchName(), player.team()), player);
                }
            }

            byId = Map.copyOf(loaded);
            byEspnId = Map.copyOf(byEspn);
            this.byNameTeam = Map.copyOf(byNameTeam);
            this.espnIdBySleeperId = Map.copyOf(espnIds);
            log.info("player catalog: {} active, {} with espn ids ({}%), {} name+team keys, "
                            + "{} espn ids across all players, {} teams",
                    byId.size(), byEspnId.size(), 100 * byEspnId.size() / Math.max(1, byId.size()),
                    this.byNameTeam.size(), this.espnIdBySleeperId.size(), teams().size());
        } catch (Exception e) {
            log.warn("player catalog refresh failed: {}", e.toString());
        }
    }

    /**
     * Reads a string field, normalising Sleeper's blanks and stray whitespace.
     * Some ids come back padded, for example gsis ids arrive with a leading space.
     */
    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNull() || value.isMissingNode()) {
            return null;
        }
        String s = value.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Players who only exist inside an archived week. Sleeper's roster is the current league,
     * so a 2007 receiver has no record there and has to be synthesised from the box score.
     */
    private final Map<String, Player> archived = new ConcurrentHashMap<>();

    public void registerArchived(Player player) {
        archived.putIfAbsent(player.id(), player);
    }

    /** ESPN id for any Sleeper player, retired or not. Null when Sleeper carries none. */
    public String espnIdForSleeperId(String sleeperId) {
        return espnIdBySleeperId.get(sleeperId);
    }

    public Player byId(String id) {
        Player player = byId.get(id);
        return player != null ? player : archived.get(id);
    }

    /** Resolves a live box score athlete back to our player record. */
    public Player byEspnId(String espnId) {
        return byEspnId.get(espnId);
    }

    /**
     * Fallback lookup for the roughly half of the league Sleeper carries no espn id for,
     * which is overwhelmingly rookies.
     */
    public Player byNameAndTeam(String displayName, String team) {
        String key = nameTeamKey(Player.normalize(displayName), team);
        return key == null ? null : byNameTeam.get(key);
    }

    private static String nameTeamKey(String searchName, String team) {
        if (searchName == null || team == null) {
            return null;
        }
        // ESPN says WSH where Sleeper says WAS. Normalise the one known disagreement.
        String t = team.equalsIgnoreCase("WSH") ? "WAS" : team.toUpperCase();
        return searchName + "|" + t;
    }

    public List<Player> all() {
        return List.copyOf(byId.values());
    }

    public TreeSet<String> teams() {
        TreeSet<String> teams = new TreeSet<>();
        byId.values().stream().filter(Player::rostered).forEach(p -> teams.add(p.team()));
        return teams;
    }

    public int size() {
        return byId.size();
    }
}
