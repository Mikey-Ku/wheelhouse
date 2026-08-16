package dev.mikeyku.wheelhouse.model;

import java.util.List;

/**
 * One entry: four players, each contributing exactly one stat.
 *
 * <p>Taking only the chosen stat is the whole game. Landing a star is not the win, deciding
 * what you want from them is, and a star's best piece is not always his most famous one.
 */
public record Roster(String id, String contestId, String owner, List<Pick> picks) {

    /** One filled slot: who, and which part of them you are taking. */
    public record Pick(Slot slot, String playerId, String option) {}

    /** One QB, one RB, two flex. */
    public static final List<Slot> SHAPE = List.of(Slot.QB, Slot.RB, Slot.FLEX, Slot.FLEX);
}
