package dev.mikeyku.wheelhouse.scoring;

import dev.mikeyku.wheelhouse.model.Slot;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-option scoring multipliers, keyed {@code slot.option}.
 *
 * <p>These are standard PPR fantasy values, not fitted ones. A touchdown is six and a
 * hundred yards is ten because that is what every fantasy player already expects, and a
 * score nobody can sanity check is worse than one that is imperfectly balanced.
 *
 * <p>The cost is that the choice within a slot is lopsided: passing yards beat passing
 * touchdowns for essentially every quarterback on expectation. What keeps the decision alive
 * is variance, since passing yards barely differ between starters while touchdowns swing
 * wildly. Taking the touchdown option is a bet on the ceiling, not a mistake.
 *
 * <p>{@code tools/calibrate.py} reports how lopsided each slot currently is.
 */
@Component
@ConfigurationProperties(prefix = "wheelhouse.scoring")
public class ScoringConfig {

    private Map<String, Double> multipliers = new HashMap<>();

    public Map<String, Double> getMultipliers() {
        return multipliers;
    }

    public void setMultipliers(Map<String, Double> multipliers) {
        this.multipliers = multipliers;
    }

    /** Returns 0 for an unconfigured option, so a missing weight scores nothing rather than double-counting. */
    public double multiplier(Slot slot, Slot.StatOption option) {
        return multipliers.getOrDefault(key(slot, option), 0.0);
    }

    public static String key(Slot slot, Slot.StatOption option) {
        return slot.name().toLowerCase() + "." + option.key();
    }
}
