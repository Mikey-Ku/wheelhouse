package dev.mikeyku.wheelhouse.model;

import java.util.List;

/**
 * One entry: four composite positions, each assembled from four or five real players.
 *
 * <p>Your quarterback is not a quarterback. It is passing yards from one, touchdowns from
 * another, and rushing yards from a third, which is coherent in a way blending a passer with
 * a receiver never was: every piece of a composite QB comes from someone playing that job.
 *
 * <p>Fourteen players, fourteen picks. The decision each time is not who you got, since the wheel
 * decides that, but which of the position's remaining parts you spend them on. The last pick
 * in a position takes whatever is left, so an early choice costs you a later one.
 */
public record Roster(String id, String contestId, String owner, List<Pick> picks) {

    /** One filled pick: who, and which part of their position they are covering. */
    public record Pick(Slot slot, String playerId, String option) {}

    /** One QB, one RB, two flex. The classic shape; a showdown's is in {@link Format}. */
    public static final List<Slot> POSITIONS = Format.CLASSIC.positions();

    public static final int TOTAL_PICKS = Format.CLASSIC.totalPicks();

    public static int partsIn(int position) {
        return Format.CLASSIC.picksIn(position);
    }

    public static int positionOf(int pickIndex) {
        return Format.CLASSIC.positionOf(pickIndex);
    }

    public static Slot slotForPick(int pickIndex) {
        return Format.CLASSIC.slotForPick(pickIndex);
    }
}
