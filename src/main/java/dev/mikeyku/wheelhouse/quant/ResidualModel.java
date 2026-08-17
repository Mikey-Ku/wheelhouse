package dev.mikeyku.wheelhouse.quant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How wrong a projection usually is, learned per stat from weeks that have already happened.
 *
 * <p>A projection is a mean, not an outcome. Pricing a roster needs the whole distribution
 * around that mean, and the shape of it differs sharply by stat: rushing yards are roughly
 * continuous and heavy tailed, touchdowns are a rare count that is zero most weeks, receptions
 * sit in between. So the model is fitted separately for each stat and split by kind.
 *
 * <p>Counts are modelled as Poisson with the projection as the rate. That is the standard
 * choice for rare independent events, it needs no fitting, and it is honest about touchdowns
 * being lumpy: a 0.6 projection means zero most weeks and occasionally two.
 *
 * <p>Continuous stats are modelled multiplicatively: actual equals projection times a random
 * factor, with the distribution of that factor learned from history. Multiplicative rather
 * than additive because a 20-yard miss means something very different on an 80-yard projection
 * than on a 250-yard one.
 */
public class ResidualModel {

    /** Below this a projection is too small for the ratio to be meaningful. */
    private static final double MIN_PROJECTION = 3.0;

    /** Stats that are counts of discrete events rather than accumulated quantities. */
    private static final List<String> COUNTS = List.of(
            "Touchdowns", "receptions", "completions", "Attempts", "Targets", "interceptions",
            "fumblesLost");

    public record Fit(String stat, int samples, double meanRatio, double sdRatio,
                      double zeroRate, boolean count) {

        public boolean usable() {
            return count || samples >= 40;
        }
    }

    private final Map<String, List<double[]>> observations = new HashMap<>();
    private final Map<String, Fit> fits = new HashMap<>();

    public static boolean isCount(String stat) {
        return COUNTS.stream().anyMatch(stat::contains);
    }

    /** Records one player-week: what was forecast, and what happened. */
    public void observe(String stat, double projected, double actual) {
        observations.computeIfAbsent(stat, k -> new ArrayList<>()).add(new double[]{projected, actual});
    }

    public void fitAll() {
        fits.clear();
        observations.forEach((stat, rows) -> fits.put(stat, fit(stat, rows)));
    }

    private Fit fit(String stat, List<double[]> rows) {
        boolean count = isCount(stat);

        long zeroes = rows.stream().filter(r -> r[1] == 0).count();
        double zeroRate = rows.isEmpty() ? 0 : (double) zeroes / rows.size();

        // Ratios are only meaningful where the forecast was big enough to divide by.
        List<Double> ratios = rows.stream()
                .filter(r -> r[0] >= MIN_PROJECTION)
                .map(r -> r[1] / r[0])
                .toList();

        if (ratios.size() < 2) {
            return new Fit(stat, rows.size(), 1.0, 0.5, zeroRate, count);
        }
        double mean = ratios.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
        double variance = ratios.stream()
                .mapToDouble(r -> (r - mean) * (r - mean))
                .sum() / (ratios.size() - 1);

        return new Fit(stat, ratios.size(), mean, Math.sqrt(variance), zeroRate, count);
    }

    public Fit fitFor(String stat) {
        Fit fit = fits.get(stat);
        if (fit != null) {
            return fit;
        }
        // An unseen stat still has to be priceable. A count falls back to pure Poisson; anything
        // else to a wide, deliberately pessimistic spread so the model never looks confident
        // about something it has never observed.
        return new Fit(stat, 0, 1.0, 0.6, 0.0, isCount(stat));
    }

    public Map<String, Fit> all() {
        return Map.copyOf(fits);
    }

    public int totalObservations() {
        return observations.values().stream().mapToInt(List::size).sum();
    }
}
