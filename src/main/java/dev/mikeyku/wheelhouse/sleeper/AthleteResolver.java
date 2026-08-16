package dev.mikeyku.wheelhouse.sleeper;

import dev.mikeyku.wheelhouse.model.GameSnapshot;
import dev.mikeyku.wheelhouse.model.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps our players onto ESPN's athlete ids.
 *
 * <p>Sleeper's {@code espn_id} covers only about half the league and the missing half is
 * almost entirely rookies, who are exactly the players filling preseason box scores. So
 * rather than trusting that field, this learns the mapping from the box scores themselves:
 * every athlete who appears in a game arrives with an id, a display name and a team, which
 * is enough to match against the catalog by normalised name.
 *
 * <p>The useful property is that it only has to resolve players who actually play, which is
 * precisely the set that needs scoring. A player nobody has seen on a field yet cannot have
 * scored anything.
 */
@Service
public class AthleteResolver {

    private static final Logger log = LoggerFactory.getLogger(AthleteResolver.class);

    private final PlayerCatalog catalog;
    private final Map<String, String> espnIdBySleeperId = new ConcurrentHashMap<>();

    public AthleteResolver(PlayerCatalog catalog) {
        this.catalog = catalog;
    }

    /** Harvests every athlete in a box score and records the ones we can identify. */
    public void learnFrom(GameSnapshot snapshot) {
        int learned = 0;
        for (Map.Entry<String, String> entry : snapshot.athleteNames().entrySet()) {
            String espnId = entry.getKey();
            Player player = catalog.byEspnId(espnId);
            if (player == null) {
                player = catalog.byNameAndTeam(entry.getValue(), snapshot.athleteTeams().get(espnId));
            }
            if (player != null && espnIdBySleeperId.put(player.id(), espnId) == null) {
                learned++;
            }
        }
        if (learned > 0) {
            log.debug("resolved {} new athletes from {} ({} known)",
                    learned, snapshot.eventId(), espnIdBySleeperId.size());
        }
    }

    /**
     * ESPN athlete id for one of our players, or null if they have never been seen in a
     * box score and Sleeper carries no id for them.
     */
    public String espnIdFor(Player player) {
        String learned = espnIdBySleeperId.get(player.id());
        return learned != null ? learned : player.espnId();
    }

    public int resolvedCount() {
        return espnIdBySleeperId.size();
    }
}
