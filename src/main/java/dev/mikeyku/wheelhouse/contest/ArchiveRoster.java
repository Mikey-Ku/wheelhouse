package dev.mikeyku.wheelhouse.contest;

import dev.mikeyku.wheelhouse.ingest.IngestService;
import dev.mikeyku.wheelhouse.model.GameSnapshot;
import dev.mikeyku.wheelhouse.model.Player;
import dev.mikeyku.wheelhouse.model.Slot;
import dev.mikeyku.wheelhouse.model.StatKey;
import dev.mikeyku.wheelhouse.sleeper.PlayerCatalog;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the wheel's player pool for an archived week out of that week's box scores.
 *
 * <p>Sleeper's roster is the league as it exists today, so it is useless for 2007. But the box
 * score already names everyone who took a snap, which is a better pool anyway: you can only
 * draft someone who was actually on a field that Sunday.
 *
 * <p>Positions are not in the payload, so they are inferred from what each player did. Someone
 * who attempted passes is a quarterback, someone who carried the ball is a back, someone who
 * caught passes is a receiver. Volume thresholds keep trick plays and wildcat snaps from
 * miscategorising anyone.
 */
@Service
public class ArchiveRoster {

    private static final int MIN_PASS_ATTEMPTS = 5;
    private static final int MIN_CARRIES = 3;
    private static final int MIN_CATCHES = 2;

    private final IngestService ingest;
    private final PlayerCatalog catalog;

    private final Map<String, List<Player>> byContest = new ConcurrentHashMap<>();

    public ArchiveRoster(IngestService ingest, PlayerCatalog catalog) {
        this.ingest = ingest;
        this.catalog = catalog;
    }

    /** Derives the week's player universe once and registers it so scoring can resolve ids. */
    public List<Player> players(String contestId) {
        return byContest.computeIfAbsent(contestId, id -> {
            List<Player> players = new ArrayList<>();
            for (GameSnapshot snapshot : ingest.snapshots(id)) {
                for (Map.Entry<String, String> athlete : snapshot.athleteNames().entrySet()) {
                    String espnId = athlete.getKey();
                    String position = infer(snapshot, espnId);
                    if (position == null) {
                        continue;
                    }
                    Player player = new Player(
                            "espn:" + espnId,
                            espnId,
                            athlete.getValue(),
                            Player.normalize(athlete.getValue()),
                            snapshot.athleteTeams().getOrDefault(espnId, "?"),
                            position,
                            0,
                            null);
                    catalog.registerArchived(player);
                    players.add(player);
                }
            }
            return List.copyOf(players);
        });
    }

    public List<Player> candidates(String contestId, Slot slot) {
        return players(contestId).stream().filter(slot::accepts).toList();
    }

    public List<Player> candidates(String contestId, Slot slot, String team) {
        return candidates(contestId, slot).stream()
                .filter(p -> p.team().equalsIgnoreCase(team))
                .toList();
    }

    public List<String> teams(String contestId, Slot slot) {
        return candidates(contestId, slot).stream()
                .map(Player::team)
                .distinct()
                .sorted()
                .toList();
    }

    /** Null means this player did nothing a roster slot can harvest, so the wheel skips them. */
    private String infer(GameSnapshot snapshot, String espnId) {
        double passes = stat(snapshot, espnId, "passing", "passingAttempts");
        if (passes >= MIN_PASS_ATTEMPTS) {
            return "QB";
        }
        double carries = stat(snapshot, espnId, "rushing", "rushingAttempts");
        double catches = stat(snapshot, espnId, "receiving", "receptions");
        if (carries >= MIN_CARRIES && carries >= catches) {
            return "RB";
        }
        if (catches >= MIN_CATCHES) {
            return "WR";
        }
        return null;
    }

    private double stat(GameSnapshot snapshot, String espnId, String category, String name) {
        Double value = snapshot.stats().get(new StatKey(espnId, category, name));
        return value == null ? 0 : value;
    }
}
