package dev.mikeyku.wheelhouse.ingest;

import dev.mikeyku.wheelhouse.model.GameSnapshot;
import dev.mikeyku.wheelhouse.model.StatDelta;
import dev.mikeyku.wheelhouse.model.StatKey;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts a stream of cumulative snapshots into a stream of deltas.
 *
 * <p>This is the whole reason snapshot polling beats an event feed here. Diffing is
 * naturally idempotent: re-ingesting the same snapshot yields nothing, and a snapshot
 * that skipped ahead because we missed a poll still produces the correct total change.
 * Nothing downstream needs to deduplicate.
 *
 * <p>State lives in memory for now. It moves to the append-only log next, at which point
 * the last snapshot becomes a read of that log rather than a field.
 */
@Service
public class IngestService {

    private static final int RECENT_DELTA_CAPACITY = 500;

    /** Keyed by contest and event, so an archived 2007 week cannot collide with this Sunday. */
    private final Map<String, GameSnapshot> latestByEvent = new ConcurrentHashMap<>();
    private final Deque<StatDelta> recentDeltas = new ArrayDeque<>();

    /**
     * Records a snapshot and returns everything that changed since the previous one.
     * The first snapshot for a game reports every stat as new rather than as a change.
     */
    public List<StatDelta> ingest(GameSnapshot snapshot) {
        GameSnapshot previous = latestByEvent.put(snapshot.key(), snapshot);
        Map<StatKey, Double> before = previous == null ? Map.of() : previous.stats();

        List<StatDelta> deltas = new ArrayList<>();
        for (Map.Entry<StatKey, Double> entry : snapshot.stats().entrySet()) {
            Double old = before.get(entry.getKey());
            if (old == null || Double.compare(old, entry.getValue()) != 0) {
                deltas.add(new StatDelta(
                        snapshot.eventId(), entry.getKey(), old, entry.getValue(), snapshot.fetchedAt()));
            }
        }

        deltas.sort(Comparator.comparing(d -> d.key().toString()));
        remember(deltas);
        return deltas;
    }

    private synchronized void remember(List<StatDelta> deltas) {
        for (StatDelta delta : deltas) {
            recentDeltas.addLast(delta);
            if (recentDeltas.size() > RECENT_DELTA_CAPACITY) {
                recentDeltas.removeFirst();
            }
        }
    }

    public synchronized List<StatDelta> recentDeltas() {
        return List.copyOf(recentDeltas);
    }

    public Collection<GameSnapshot> snapshots() {
        return latestByEvent.values();
    }

    public GameSnapshot snapshot(String contestId, String eventId) {
        return latestByEvent.get(contestId + "|" + eventId);
    }

    /** Every game we hold for one week. */
    public List<GameSnapshot> snapshots(String contestId) {
        return latestByEvent.values().stream()
                .filter(s -> s.contestId().equals(contestId))
                .toList();
    }

    public boolean hasContest(String contestId) {
        return latestByEvent.values().stream().anyMatch(s -> s.contestId().equals(contestId));
    }

    /**
     * Who a team played that week, read off the box score rather than a schedule table.
     *
     * <p>The snapshots already name both sides, so a separate schedule feed would be a second
     * source of truth for something we can already see. This reveals nothing a draft should be
     * hiding: the fixture list is public long before kickoff, and knowing the matchup is exactly
     * the sort of thing a pick is supposed to turn on.
     */
    public String opponentOf(String contestId, String team) {
        if (team == null) {
            return null;
        }
        for (GameSnapshot snapshot : snapshots(contestId)) {
            List<String> sides = snapshot.athleteTeams().values().stream().distinct().toList();
            if (sides.contains(team)) {
                return sides.stream().filter(t -> !t.equals(team)).findFirst().orElse(null);
            }
        }
        return null;
    }

    /**
     * Value of one stat within one week.
     *
     * <p>A player appears in exactly one game per week, so scanning that week's snapshots is
     * cheap. This becomes an index read once snapshots move to the log.
     */
    public Double statValue(String contestId, StatKey key) {
        for (GameSnapshot snapshot : latestByEvent.values()) {
            if (!snapshot.contestId().equals(contestId)) {
                continue;
            }
            Double value = snapshot.stats().get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
