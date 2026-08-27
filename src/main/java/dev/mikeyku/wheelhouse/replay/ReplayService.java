package dev.mikeyku.wheelhouse.replay;

import dev.mikeyku.wheelhouse.ingest.IngestService;
import dev.mikeyku.wheelhouse.model.GameSnapshot;
import dev.mikeyku.wheelhouse.model.StatDelta;
import dev.mikeyku.wheelhouse.model.StatKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Streams a finished game back through ingestion as though it were still being played.
 *
 * <p>The archive path reads a final box score once. The live path reads the same game over and
 * over as it changes, and those are different problems: the differ only earns its keep on the
 * second reading, a missed poll has to be caught up by the next one, and a stat correction
 * arrives as a number going down. None of that is exercised by replaying a week that is already
 * over, which is why it has never run.
 *
 * <p>Waiting for a real Sunday to find out is a bad trade. This takes a completed reading and
 * emits it as a sequence of partial ones, so every part of the live path can be driven and
 * asserted on a Tuesday in August.
 *
 * <p>The staging is deliberately crude. Cumulative totals are scaled by the fraction of the game
 * elapsed and floored, which is not how a real game accumulates but is the only property the
 * pipeline downstream actually depends on: totals never decrease, except when a correction says
 * they do, and that case is modelled separately.
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final IngestService ingest;

    public ReplayService(IngestService ingest) {
        this.ingest = ingest;
    }

    /**
     * One poll's worth of the game, and what ingesting it changed.
     *
     * @param magnitude summed absolute value of every stat in the reading. The key count barely
     *                  moves during a game, because a box score lists everyone who has been on
     *                  the field whether or not they have done anything; what grows is the
     *                  numbers, so that is what a replay has to be measured on.
     */
    public record Tick(int stage, String state, int statsPresent, double magnitude,
                       List<StatDelta> deltas) {}

    public record Replay(String eventId, int stages, List<Tick> ticks) {

        /** Deltas across the whole replay. A replay that produces none has proved nothing. */
        public int totalDeltas() {
            return ticks.stream().mapToInt(t -> t.deltas().size()).sum();
        }
    }

    /**
     * Plays a finished reading through ingestion in {@code stages} steps.
     *
     * @param finished the authoritative box score, as the archive or a final poll would see it
     */
    public Replay play(GameSnapshot finished, int stages) {
        if (stages < 1) {
            throw new IllegalArgumentException("a replay needs at least one stage");
        }
        List<Tick> ticks = new ArrayList<>();
        for (int stage = 1; stage <= stages; stage++) {
            GameSnapshot partial = at(finished, stage, stages);
            List<StatDelta> deltas = ingest.ingest(partial);
            double magnitude = partial.stats().values().stream().mapToDouble(Math::abs).sum();
            ticks.add(new Tick(stage, partial.state(), partial.stats().size(),
                    Math.round(magnitude * 100.0) / 100.0, deltas));
        }
        Replay replay = new Replay(finished.eventId(), stages, ticks);
        log.info("replayed {} in {} stages: {} deltas", finished.eventId(), stages,
                replay.totalDeltas());
        return replay;
    }

    /**
     * The game as it looked a fraction of the way through.
     *
     * <p>Stats are floored rather than rounded so a touchdown appears the moment the fraction
     * reaches it rather than half a stage early, which keeps counts integral the way a real box
     * score keeps them.
     *
     * <p>Every key is present at every stage, including the ones that floor to zero. That is what
     * ESPN actually serves: the box score lists everyone who has taken the field, with zeroes
     * against the ones who have not done anything yet. Dropping them would make the final poll
     * announce a flood of changes that never happened, and would make the last reading differ
     * from the authoritative one it is supposed to equal.
     */
    public GameSnapshot at(GameSnapshot finished, int stage, int stages) {
        boolean last = stage >= stages;
        double fraction = (double) stage / stages;

        Map<StatKey, Double> partial = new LinkedHashMap<>();
        for (Map.Entry<StatKey, Double> entry : finished.stats().entrySet()) {
            partial.put(entry.getKey(),
                    last ? entry.getValue() : scale(entry.getValue(), fraction));
        }

        return new GameSnapshot(
                finished.contestId(), finished.eventId(), finished.name(),
                last ? "post" : "in",
                last ? "Final" : "Q" + Math.min(4, stage),
                finished.fetchedAt().plus(Duration.ofMinutes(15L * stage)),
                Map.copyOf(partial), finished.athleteNames(), finished.athleteTeams());
    }

    /** Negative yardage scales toward zero from the other side, so the sign is preserved. */
    private double scale(double total, double fraction) {
        double scaled = total * fraction;
        return total < 0 ? -Math.floor(-scaled) : Math.floor(scaled);
    }

    /**
     * The same reading with one stat corrected after the fact.
     *
     * <p>Real box scores are revised: a catch is reassigned, a run is ruled a fumble. The value
     * goes down, which is the one direction a cumulative feed is not supposed to move, and a
     * pipeline that assumes monotonic growth quietly keeps scoring the old number.
     */
    public GameSnapshot corrected(GameSnapshot snapshot, StatKey key, double correctedTo) {
        Map<StatKey, Double> stats = new LinkedHashMap<>(snapshot.stats());
        stats.put(key, correctedTo);
        return new GameSnapshot(
                snapshot.contestId(), snapshot.eventId(), snapshot.name(),
                snapshot.state(), "Final (corrected)",
                snapshot.fetchedAt().plus(Duration.ofMinutes(30)),
                Map.copyOf(stats), snapshot.athleteNames(), snapshot.athleteTeams());
    }
}
