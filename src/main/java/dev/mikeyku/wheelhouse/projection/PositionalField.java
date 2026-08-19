package dev.mikeyku.wheelhouse.projection;

import dev.mikeyku.wheelhouse.model.Player;
import dev.mikeyku.wheelhouse.model.Slot;
import dev.mikeyku.wheelhouse.wheel.WheelPool;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Where one projection sits among everyone who could have filled the same part this week.
 *
 * <p>The game scores raw points. Nothing about it rewards beating a forecast, so "will he clear
 * his own line" is the wrong question to judge a card by, and colouring one red for missing it
 * says a good pick is a bad one. A quarterback projected for 290 passing yards who has fallen
 * short four weeks running is still the best passing-yards option on the board, because the
 * only thing you are choosing between is the other parts in front of you and the other players
 * who could have come up instead.
 *
 * <p>So the benchmark is the field: of everyone eligible at this slot who is expected to do
 * anything at all at this part, what fraction does this player beat? That is the number that
 * answers "is this a good pick", which is the question actually being asked.
 *
 * <p>Ranked on the raw projection rather than points, because the multiplier is constant inside
 * a part and therefore cannot change the ordering.
 */
@Service
public class PositionalField {

    /** Below this the field is too thin for a percentile to mean anything. */
    private static final int MIN_FIELD = 10;

    private final WheelPool pool;
    private final ProjectionService projections;

    /** Sorted ascending, one entry per eligible player with a non-zero forecast. */
    private final Map<String, double[]> fields = new ConcurrentHashMap<>();

    public PositionalField(WheelPool pool, ProjectionService projections) {
        this.pool = pool;
        this.projections = projections;
    }

    /**
     * Percentile of this projection within its field, or null when the field is too small.
     *
     * <p>Zero-projection players are excluded. Two thirds of any position is deep bench nobody
     * expects to record the stat, and counting them would put every starter in the ninetieth
     * percentile and make the number useless.
     */
    public Integer percentile(String contestId, Slot slot, Slot.StatOption option, Double projected) {
        if (projected == null || projected <= 0) {
            return null;
        }
        double[] field = field(contestId, slot, option);
        if (field.length < MIN_FIELD) {
            return null;
        }
        int below = 0;
        while (below < field.length && field[below] < projected) {
            below++;
        }
        return (int) Math.round(100.0 * below / field.length);
    }

    /** The middling option at this part, so a card can say what ordinary looks like. */
    public Double median(String contestId, Slot slot, Slot.StatOption option) {
        double[] field = field(contestId, slot, option);
        return field.length < MIN_FIELD ? null : field[field.length / 2];
    }

    private double[] field(String contestId, Slot slot, Slot.StatOption option) {
        return fields.computeIfAbsent(contestId + "|" + slot.name() + "|" + option.key(), key ->
                pool.candidates(contestId, slot).stream()
                        .map(p -> projections.projected(contestId, p, option))
                        .filter(Objects::nonNull)
                        .mapToDouble(Double::doubleValue)
                        .filter(v -> v > 0)
                        .sorted()
                        .toArray());
    }
}
