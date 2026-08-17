package dev.mikeyku.wheelhouse.quant;

import dev.mikeyku.wheelhouse.model.Player;
import dev.mikeyku.wheelhouse.model.Slot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Simulates what a composite roster might actually score, rather than what it is projected to.
 *
 * <p>The projected total is a sum of means, which is the one number that tells you nothing
 * about risk. Two rosters projected at 90 points are not the same bet if one is built on
 * receptions and the other on touchdowns. This produces the distribution instead.
 *
 * <p><b>Correlation is the whole problem.</b> The parts of a roster are not independent. A
 * quarterback's passing yards and his own receiver's receiving yards are close to the same
 * event counted twice. A back's carries rise exactly when his quarterback's attempts fall.
 * Summing independent draws would understate the spread badly on a stacked roster and
 * overstate it on a diversified one, which is precisely the thing a line has to get right.
 *
 * <p>So correlated draws are produced with a Gaussian copula: sample standard normals, impose
 * the correlation structure, push them through the normal CDF to uniforms, then invert each
 * part's own marginal. That decouples "how do these move together" from "what shape is each
 * one", which is what lets a Poisson touchdown count and a heavy-tailed yardage total live in
 * the same simulation.
 *
 * <p>Known limitation, stated rather than buried: a Gaussian copula has no tail dependence. It
 * will under-price the case where everything goes right at once, which is exactly the case a
 * book cares most about. Fixing that means a t-copula, and that is not built here.
 */
@Service
public class RosterPricer {

    /** Same game, same side of the ball. Two receivers on one offence rise and fall together. */
    private static final double SAME_TEAM = 0.35;

    /** Passing volume and rushing volume trade off against each other under game script. */
    private static final double SAME_TEAM_OPPOSED = -0.20;

    /** Everyone in one game shares pace, weather and how competitive it stayed. */
    private static final double SAME_GAME = 0.12;

    public record Leg(String playerId, String team, String opponent, Slot slot,
                      Slot.StatOption option, double projected, double multiplier) {}

    public record Distribution(double projectedTotal, double mean, double median, double sd,
                               double p05, double p25, double p75, double p95,
                               double fairLine, int trials, List<String> notes) {}

    /**
     * Runs the simulation.
     *
     * @param seed fixed so a given roster always prices identically; this is analysis, and an
     *             answer that moves when you refresh it is not an answer
     */
    public Distribution price(List<Leg> legs, ResidualModel residuals, int trials, long seed) {
        if (legs.isEmpty()) {
            return new Distribution(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of("no legs"));
        }
        Random random = new Random(seed);
        double[][] chol = cholesky(correlationMatrix(legs));
        double[] totals = new double[trials];

        for (int t = 0; t < trials; t++) {
            double[] independent = new double[legs.size()];
            for (int i = 0; i < legs.size(); i++) {
                independent[i] = random.nextGaussian();
            }
            double[] correlated = multiply(chol, independent);

            double total = 0;
            for (int i = 0; i < legs.size(); i++) {
                Leg leg = legs.get(i);
                double uniform = normalCdf(correlated[i]);
                double drawn = invertMarginal(leg, residuals.fitFor(leg.option().stats().get(0).stat()),
                        uniform, random);
                total += drawn * leg.multiplier();
            }
            totals[t] = total;
        }

        java.util.Arrays.sort(totals);
        double mean = java.util.Arrays.stream(totals).average().orElse(0);
        double sd = Math.sqrt(java.util.Arrays.stream(totals)
                .map(v -> (v - mean) * (v - mean)).sum() / Math.max(1, trials - 1));
        double projected = legs.stream().mapToDouble(l -> l.projected() * l.multiplier()).sum();

        List<String> notes = new ArrayList<>();
        notes.add("gaussian copula, no tail dependence");
        long unfitted = legs.stream()
                .filter(l -> !residuals.fitFor(l.option().stats().get(0).stat()).usable())
                .count();
        if (unfitted > 0) {
            notes.add(unfitted + " of " + legs.size() + " legs priced on fallback spreads");
        }

        return new Distribution(
                round(projected), round(mean), round(pct(totals, 0.50)), round(sd),
                round(pct(totals, 0.05)), round(pct(totals, 0.25)),
                round(pct(totals, 0.75)), round(pct(totals, 0.95)),
                // A fair line is the median, not the mean: it is the number that splits the
                // outcomes in half, which is what an over/under has to do.
                round(pct(totals, 0.50)), trials, notes);
    }

    /** Draws one leg's outcome at the given quantile of its own distribution. */
    private double invertMarginal(Leg leg, ResidualModel.Fit fit, double uniform, Random random) {
        if (fit.count()) {
            return poissonQuantile(Math.max(0, leg.projected()), uniform);
        }
        // Lognormal keeps the draw non-negative and gives yardage its right-hand tail.
        double sigma = Math.sqrt(Math.log(1 + Math.pow(fit.sdRatio() / Math.max(0.05, fit.meanRatio()), 2)));
        double mu = Math.log(Math.max(0.01, fit.meanRatio())) - 0.5 * sigma * sigma;
        return Math.max(0, leg.projected() * Math.exp(mu + sigma * normalQuantile(uniform)));
    }

    /**
     * Correlation between every pair of legs.
     *
     * <p>Deliberately coarse and hand-set rather than estimated. Estimating a full
     * player-by-player correlation matrix from box scores needs far more player-weeks than the
     * archive currently holds, and a badly estimated correlation is worse than an openly
     * approximate one. These three numbers encode the structure that actually matters.
     */
    private double[][] correlationMatrix(List<Leg> legs) {
        int n = legs.size();
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) {
            m[i][i] = 1.0;
            for (int j = i + 1; j < n; j++) {
                double rho = pairCorrelation(legs.get(i), legs.get(j));
                m[i][j] = rho;
                m[j][i] = rho;
            }
        }
        return m;
    }

    private double pairCorrelation(Leg a, Leg b) {
        boolean sameGame = a.team() != null && b.team() != null
                && (a.team().equals(b.team())
                    || a.team().equals(b.opponent()) || b.team().equals(a.opponent()));
        if (!sameGame) {
            return 0.0;
        }
        if (!a.team().equals(b.team())) {
            // Opposing offences: one team scoring keeps the other throwing. Mildly positive.
            return SAME_GAME;
        }
        boolean aPass = a.option().stats().get(0).category().equals("passing")
                || a.option().stats().get(0).category().equals("receiving");
        boolean bPass = b.option().stats().get(0).category().equals("passing")
                || b.option().stats().get(0).category().equals("receiving");
        return aPass == bPass ? SAME_TEAM : SAME_TEAM_OPPOSED;
    }

    /** Cholesky, falling back to the identity if the hand-set matrix is not positive definite. */
    private double[][] cholesky(double[][] m) {
        int n = m.length;
        double[][] l = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = 0;
                for (int k = 0; k < j; k++) {
                    sum += l[i][k] * l[j][k];
                }
                if (i == j) {
                    double d = m[i][i] - sum;
                    if (d <= 1e-9) {
                        return identity(n);
                    }
                    l[i][j] = Math.sqrt(d);
                } else {
                    l[i][j] = (m[i][j] - sum) / l[j][j];
                }
            }
        }
        return l;
    }

    private double[][] identity(int n) {
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) {
            m[i][i] = 1.0;
        }
        return m;
    }

    private double[] multiply(double[][] l, double[] v) {
        int n = v.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int j = 0; j <= i; j++) {
                sum += l[i][j] * v[j];
            }
            out[i] = sum;
        }
        return out;
    }

    private double poissonQuantile(double lambda, double uniform) {
        if (lambda <= 0) {
            return 0;
        }
        double cumulative = Math.exp(-lambda);
        double term = cumulative;
        int k = 0;
        while (cumulative < uniform && k < 40) {
            k++;
            term *= lambda / k;
            cumulative += term;
        }
        return k;
    }

    private double normalCdf(double z) {
        return 0.5 * (1 + erf(z / Math.sqrt(2)));
    }

    private double erf(double x) {
        // Abramowitz and Stegun 7.1.26, plenty for simulation work.
        double t = 1 / (1 + 0.3275911 * Math.abs(x));
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t
                + 0.254829592) * t * Math.exp(-x * x);
        return x >= 0 ? y : -y;
    }

    /** Acklam's inverse normal approximation. */
    private double normalQuantile(double p) {
        double q = Math.min(Math.max(p, 1e-9), 1 - 1e-9);
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
                3.754408661907416e+00};
        double plow = 0.02425;
        if (q < plow) {
            double x = Math.sqrt(-2 * Math.log(q));
            return (((((c[0] * x + c[1]) * x + c[2]) * x + c[3]) * x + c[4]) * x + c[5])
                    / ((((d[0] * x + d[1]) * x + d[2]) * x + d[3]) * x + 1);
        }
        if (q > 1 - plow) {
            double x = Math.sqrt(-2 * Math.log(1 - q));
            return -(((((c[0] * x + c[1]) * x + c[2]) * x + c[3]) * x + c[4]) * x + c[5])
                    / ((((d[0] * x + d[1]) * x + d[2]) * x + d[3]) * x + 1);
        }
        double x = q - 0.5;
        double r = x * x;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * x
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }

    private double pct(double[] sorted, double p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(p * sorted.length))];
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
