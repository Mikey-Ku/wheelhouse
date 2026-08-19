package dev.mikeyku.wheelhouse.scoring;

import dev.mikeyku.wheelhouse.entry.EntryRecord;
import dev.mikeyku.wheelhouse.model.Player;
import dev.mikeyku.wheelhouse.model.Roster;
import dev.mikeyku.wheelhouse.model.Slot;
import dev.mikeyku.wheelhouse.sleeper.PlayerCatalog;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * How much of your own board you actually took.
 *
 * <p>A raw score mostly measures the wheel. Somebody dealt Lamar Jackson and Ja'Marr Chase beats
 * somebody dealt two backups every time, and telling them so is not feedback. The only thing a
 * player controls is which stat they take off each man they are handed, so that is the only
 * thing worth scoring them on.
 *
 * <p>So: hold the players fixed, exactly the ones the wheel dealt, and ask what the best possible
 * assignment of parts to them would have been. That is the ceiling of the board you were given.
 * Your score over that ceiling is skill with luck divided out, because the ceiling moves with
 * your draw.
 *
 * <p>Each position is an assignment problem, and a small one: the parts and the players are
 * equal in number and each part goes once, so it is a perfect matching over at most five
 * elements. A hundred and twenty permutations is not worth an algorithm, so it is brute forced.
 */
@Service
public class CaptureRate {

    private final PlayerCatalog catalog;
    private final ScoringService scoring;

    public CaptureRate(PlayerCatalog catalog, ScoringService scoring) {
        this.catalog = catalog;
        this.scoring = scoring;
    }

    /**
     * The single exchange that would have gained the most.
     *
     * <p>A whole optimal assignment is hard to argue with and harder to learn from. One swap is
     * a sentence: you took this off him, and it belonged on the other one.
     */
    public record Swap(String fromPlayer, String fromPart, double fromScored,
                       String toPlayer, String toPart, double toScored, double gain) {}

    public record Result(double scored, double ceiling, int capturePercent, Swap worstCall) {}

    /** Null until the roster is complete, since a half-built board has no ceiling yet. */
    public Result of(EntryRecord entry) {
        if (!entry.complete()) {
            return null;
        }
        double scored = 0;
        double ceiling = 0;
        Swap worst = null;

        for (int position = 0; position < Roster.POSITIONS.size(); position++) {
            List<EntryRecord.PickRecord> picks = entry.picksInPosition(position).stream()
                    .filter(EntryRecord.PickRecord::filled)
                    .toList();
            Slot slot = Roster.POSITIONS.get(position);
            List<Slot.StatOption> parts = slot.options();
            if (picks.size() != parts.size()) {
                continue;
            }

            // points[player][part], actual rather than projected: the ceiling is what was really
            // there to be taken, not what anyone thought would be.
            double[][] points = new double[picks.size()][parts.size()];
            for (int i = 0; i < picks.size(); i++) {
                Player player = catalog.byId(picks.get(i).playerId());
                for (int j = 0; j < parts.size(); j++) {
                    points[i][j] = player == null ? 0
                            : scoring.scoreOne(entry.contestId(), slot, player, parts.get(j)).points();
                }
            }

            int[] chosen = new int[picks.size()];
            for (int i = 0; i < picks.size(); i++) {
                chosen[i] = indexOf(parts, picks.get(i).option());
                scored += chosen[i] < 0 ? 0 : points[i][chosen[i]];
            }
            ceiling += best(points, new int[points.length], new boolean[points.length], 0);
            worst = better(worst, bestSwap(entry, slot, picks, parts, points, chosen));
        }

        // A ceiling of zero does not mean a perfect roster. It means the week's stats are not
        // in memory, so every option priced at nothing. Reporting 100% there would congratulate
        // someone for a scoreboard the server had simply failed to load.
        if (ceiling <= 0) {
            return null;
        }
        int percent = (int) Math.round(100.0 * scored / ceiling);
        return new Result(round(scored), round(ceiling), Math.min(percent, 100), worst);
    }

    /** Highest total over every one-to-one assignment of parts to players. */
    private double best(double[][] points, int[] pick, boolean[] used, int row) {
        if (row == points.length) {
            double total = 0;
            for (int i = 0; i < pick.length; i++) {
                total += points[i][pick[i]];
            }
            return total;
        }
        double top = 0;
        for (int col = 0; col < points[row].length; col++) {
            if (used[col]) {
                continue;
            }
            used[col] = true;
            pick[row] = col;
            top = Math.max(top, best(points, pick, used, row + 1));
            used[col] = false;
        }
        return top;
    }

    /** The most valuable exchange of two parts inside one position. */
    private Swap bestSwap(EntryRecord entry, Slot slot, List<EntryRecord.PickRecord> picks,
                          List<Slot.StatOption> parts, double[][] points, int[] chosen) {
        Swap best = null;
        for (int a = 0; a < picks.size(); a++) {
            for (int b = a + 1; b < picks.size(); b++) {
                if (chosen[a] < 0 || chosen[b] < 0) {
                    continue;
                }
                double now = points[a][chosen[a]] + points[b][chosen[b]];
                double swapped = points[a][chosen[b]] + points[b][chosen[a]];
                double gain = swapped - now;
                if (gain <= 0.01 || (best != null && gain <= best.gain())) {
                    continue;
                }
                best = new Swap(
                        nameOf(picks.get(a)), parts.get(chosen[a]).label(), round(points[a][chosen[a]]),
                        nameOf(picks.get(b)), parts.get(chosen[b]).label(), round(points[b][chosen[b]]),
                        round(gain));
            }
        }
        return best;
    }

    private Swap better(Swap a, Swap b) {
        if (a == null) {
            return b;
        }
        return b != null && b.gain() > a.gain() ? b : a;
    }

    private String nameOf(EntryRecord.PickRecord pick) {
        Player player = catalog.byId(pick.playerId());
        return player == null || player.name() == null ? "?" : player.name();
    }

    private int indexOf(List<Slot.StatOption> parts, String key) {
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).key().equalsIgnoreCase(key)) {
                return i;
            }
        }
        return -1;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
