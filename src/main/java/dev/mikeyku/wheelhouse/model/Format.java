package dev.mikeyku.wheelhouse.model;

import java.util.List;

/**
 * The shape of a roster: which positions it has and how many picks each one takes.
 *
 * <p>Classic fills every part of every position, fourteen picks drawn from a full Sunday. A
 * single game cannot supply that: it has two quarterbacks and the classic quarterback needs
 * four different ones, since nobody appears on a roster twice. Showdown is the single-game
 * shape: fewer picks than parts in the backfield positions, so part of the decision becomes
 * which parts to leave empty.
 */
public enum Format {

    CLASSIC(List.of(Slot.QB, Slot.RB, Slot.FLEX, Slot.FLEX), null),
    SHOWDOWN(List.of(Slot.QB, Slot.RB, Slot.FLEX), List.of(2, 2, 3));

    private final List<Slot> positions;
    /** Null means every part of the position is filled. */
    private final List<Integer> picks;

    Format(List<Slot> positions, List<Integer> picks) {
        this.positions = positions;
        this.picks = picks;
    }

    public List<Slot> positions() {
        return positions;
    }

    public int picksIn(int position) {
        return picks == null ? positions.get(position).options().size() : picks.get(position);
    }

    public int totalPicks() {
        int total = 0;
        for (int p = 0; p < positions.size(); p++) {
            total += picksIn(p);
        }
        return total;
    }

    /** Positions hold different numbers of picks, so a flat pick index is walked, not divided. */
    public int positionOf(int pickIndex) {
        int remaining = pickIndex;
        for (int position = 0; position < positions.size(); position++) {
            int n = picksIn(position);
            if (remaining < n) {
                return position;
            }
            remaining -= n;
        }
        throw new IllegalArgumentException("no position holds pick " + pickIndex);
    }

    public Slot slotForPick(int pickIndex) {
        return positions.get(positionOf(pickIndex));
    }
}
