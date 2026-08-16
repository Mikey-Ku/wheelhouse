package dev.mikeyku.wheelhouse.model;

import java.util.List;

/**
 * One entry: four composite positions, each assembled from three real players.
 *
 * <p>Your quarterback is not a quarterback. It is passing yards from one, touchdowns from
 * another, and rushing yards from a third, which is coherent in a way blending a passer with
 * a receiver never was: every piece of a composite QB comes from someone playing that job.
 *
 * <p>Twelve players, twelve picks. The decision each time is not who you got, since the wheel
 * decides that, but which of the position's remaining parts you spend them on. The third pick
 * in a position takes whatever is left, so an early choice costs you a later one.
 */
public record Roster(String id, String contestId, String owner, List<Pick> picks) {

    /** One filled pick: who, and which part of their position they are covering. */
    public record Pick(Slot slot, String playerId, String option) {}

    /** One QB, one RB, two flex. */
    public static final List<Slot> POSITIONS = List.of(Slot.QB, Slot.RB, Slot.FLEX, Slot.FLEX);

    /** Every position offers three parts, and a full roster fills all of them. */
    public static final int PARTS_PER_POSITION = 3;

    public static final int TOTAL_PICKS = POSITIONS.size() * PARTS_PER_POSITION;

    public static Slot slotForPick(int pickIndex) {
        return POSITIONS.get(pickIndex / PARTS_PER_POSITION);
    }

    public static int positionOf(int pickIndex) {
        return pickIndex / PARTS_PER_POSITION;
    }
}
