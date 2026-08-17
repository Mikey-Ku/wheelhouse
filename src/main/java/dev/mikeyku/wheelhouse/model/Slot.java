package dev.mikeyku.wheelhouse.model;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The four roster positions and the stats each one can harvest.
 *
 * <p>Two flex slots share the same definition, so the enum has three constants rather than
 * four. A roster is one QB, one RB, and two FLEX.
 *
 * <p>The stat options are what the anatomy naming sits on top of: an RB's legs are rushing
 * yards, their hands are receptions.
 */
public enum Slot {

    // Completions were the original third option and had to go. Every starting quarterback
    // projects to roughly the same completions (20 plus or minus 1.3) and the same passing
    // yards, so the choice was decided by noise. Rushing yards has the widest spread of any
    // stat in the game, from about 2 to about 39 a week, which turns the QB slot into a real
    // question: the pocket passer's arm, or the scrambler's legs.
    QB(Set.of("QB"), List.of(
            new StatOption("arm", "Arm", "passing", "passingYards", "pass_yd"),
            new StatOption("legs", "Legs", "rushing", "rushingYards", "rush_yd"),
            new StatOption("shoulders", "Shoulders", "passing", "passingTouchdowns", "pass_td"))),

    RB(Set.of("RB"), List.of(
            new StatOption("legs", "Legs", "rushing", "rushingYards", "rush_yd"),
            new StatOption("hands", "Hands", "receiving", "receptions", "rec"),
            new StatOption("chest", "Chest", "receiving", "receivingYards", "rec_yd"))),

    FLEX(Set.of("WR", "TE"), List.of(
            new StatOption("chest", "Chest", "receiving", "receivingYards", "rec_yd"),
            new StatOption("hands", "Hands", "receiving", "receptions", "rec"),
            new StatOption("feet", "Feet", "receiving", "receivingTouchdowns", "rec_td")));

    /**
     * One harvestable stat, with the anatomy label the UI shows for it.
     *
     * <p>Two vocabularies, deliberately carried side by side. {@code category} and {@code stat}
     * name the stat as ESPN reports it in a box score; {@code projectionStat} names the same
     * thing as Sleeper reports it in a projection. They agree on nothing (ESPN says
     * receivingTouchdowns, Sleeper says rec_td) and the two feeds key players differently as
     * well, so the bridge has to live somewhere. This is the one place both names are stated
     * together.
     */
    public record StatOption(String key, String label, String category, String stat,
                             String projectionStat) {
        public StatKey keyFor(String espnAthleteId) {
            return new StatKey(espnAthleteId, category, stat);
        }
    }

    private final Set<String> positions;
    private final List<StatOption> options;

    Slot(Set<String> positions, List<StatOption> options) {
        this.positions = positions;
        this.options = options;
    }

    public Set<String> positions() {
        return positions;
    }

    public List<StatOption> options() {
        return options;
    }

    public boolean accepts(Player player) {
        return player.position() != null && positions.contains(player.position());
    }

    public Optional<StatOption> option(String key) {
        return options.stream().filter(o -> o.key().equalsIgnoreCase(key)).findFirst();
    }
}
