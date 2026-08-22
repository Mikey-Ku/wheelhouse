package dev.mikeyku.wheelhouse.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The three roster positions and the parts each one can harvest.
 *
 * <p>Four parts at quarterback and running back, three at flex, so a full roster is fourteen
 * real players across four positions.
 *
 * <p>Every part is a stat standard PPR actually scores, and that rule cost three of them.
 * Completions, carries and targets are all real and all predictive, and no fantasy league pays
 * for any of them, so their weights had to be invented rather than inherited. A made-up number
 * is exactly the thing a player cannot check, which makes it the wrong thing to ask them to
 * bet a pick on.
 *
 * <p>Cutting them made the game harder as well as shorter: measured over seventeen thousand
 * player-weeks, receptions and targets and carries were the only parts that beat their
 * projection more than half the time. What is left is yardage, which clears about 47%, and
 * touchdowns, which clear far less.
 *
 * <p>Every part is a stat that exists in <em>both</em> feeds: Sleeper has to project it and
 * ESPN has to report it. That rules out otherwise appealing options like first downs and
 * forty-yard receptions, which Sleeper forecasts but ESPN's box score never reports, and which
 * would therefore have projected beautifully and scored zero forever.
 *
 * <p>Hard mode swaps the last part of each position for one that can only cost you points.
 */
public enum Slot {

    QB(Set.of("QB"),
            List.of(
                    StatOption.of("arm", "Arm", "passing", "passingYards", "pass_yd"),
                    StatOption.of("shoulders", "Shoulders", "passing", "passingTouchdowns", "pass_td"),
                    StatOption.of("legs", "Legs", "rushing", "rushingYards", "rush_yd"),
                    StatOption.of("cleats", "Cleats", "rushing", "rushingTouchdowns", "rush_td")),
            StatOption.of("head", "Head", "passing", "interceptions", "pass_int")),

    RB(Set.of("RB"),
            List.of(
                    StatOption.of("legs", "Legs", "rushing", "rushingYards", "rush_yd"),
                    StatOption.of("hands", "Hands", "receiving", "receptions", "rec"),
                    StatOption.of("chest", "Chest", "receiving", "receivingYards", "rec_yd"),
                    StatOption.totalTouchdowns("nose", "Nose")),
            StatOption.of("grip", "Grip", "fumbles", "fumblesLost", "fum_lost")),

    FLEX(Set.of("WR", "TE"),
            List.of(
                    StatOption.of("hands", "Hands", "receiving", "receptions", "rec"),
                    StatOption.of("chest", "Chest", "receiving", "receivingYards", "rec_yd"),
                    StatOption.totalTouchdowns("nose", "Nose")),
            StatOption.of("grip", "Grip", "fumbles", "fumblesLost", "fum_lost"));

    /** One ESPN box score field, named as ESPN names it. */
    public record StatRef(String category, String stat) {
        public StatKey keyFor(String espnAthleteId) {
            return new StatKey(espnAthleteId, category, stat);
        }
    }

    /**
     * One harvestable part.
     *
     * <p>A part can span more than one underlying stat. "Total touchdowns" is rushing plus
     * receiving, which is one idea to a player and two separate fields in both feeds, so the
     * components are summed rather than modelled as separate parts.
     *
     * <p>Two vocabularies, carried side by side. {@code stats} name the components as ESPN
     * reports them in a box score; {@code projectionStats} name the same things as Sleeper
     * reports them in a projection. They agree on nothing (ESPN says receivingTouchdowns,
     * Sleeper says rec_td) and the feeds key players differently as well, so the bridge has to
     * live somewhere. This is the one place both are stated together.
     */
    public record StatOption(String key, String label, List<StatRef> stats,
                             List<String> projectionStats, String description) {

        static StatOption of(String key, String label, String category, String stat, String projectionStat) {
            return new StatOption(key, label, List.of(new StatRef(category, stat)),
                    List.of(projectionStat), humanise(stat));
        }

        static StatOption totalTouchdowns(String key, String label) {
            return new StatOption(key, label,
                    List.of(new StatRef("rushing", "rushingTouchdowns"),
                            new StatRef("receiving", "receivingTouchdowns")),
                    List.of("rush_td", "rec_td"),
                    "total touchdowns");
        }

        /** Splits only at lowercase-to-uppercase boundaries, so acronyms survive. */
        private static String humanise(String stat) {
            return stat.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
        }

        /**
         * A short unit for the raw number.
         *
         * <p>"1.9 projected x 4" asks the reader to look back up at the card heading to find
         * out what 1.9 counts, and by the time five cards are on screen that is five lookups.
         * "1.9 TD projected x 4" answers it in place.
         *
         * <p>Derived from the description rather than stored, so a new part cannot be added
         * with a stat name and no unit to go with it.
         */
        public String unit() {
            String s = description();
            if (s.contains("touchdown")) return "TD";
            if (s.contains("yards")) return "yds";
            if (s.contains("receptions")) return "rec";
            if (s.contains("targets")) return "tgt";
            if (s.contains("attempts")) return "att";
            if (s.contains("completions")) return "cmp";
            if (s.contains("interceptions")) return "int";
            if (s.contains("fumbles")) return "fum";
            return "";
        }

        /** True when this part is scored on touchdowns, which is what makes it a gamble. */
        public boolean volatile_() {
            return stats.stream().anyMatch(s -> s.stat().toLowerCase().contains("touchdown"));
        }
    }

    private final Set<String> positions;
    private final List<StatOption> options;
    private final StatOption penalty;

    Slot(Set<String> positions, List<StatOption> options, StatOption penalty) {
        this.positions = positions;
        this.options = options;
        this.penalty = penalty;
    }

    public Set<String> positions() {
        return positions;
    }

    public List<StatOption> options() {
        return options;
    }

    /**
     * The parts in play. Hard mode trades the last one for something that can only cost you, so
     * the roster is the same size either way.
     */
    public List<StatOption> options(boolean hard) {
        if (!hard) {
            return options;
        }
        List<StatOption> swapped = new ArrayList<>(options.subList(0, options.size() - 1));
        swapped.add(penalty);
        return List.copyOf(swapped);
    }

    public StatOption penalty() {
        return penalty;
    }

    /**
     * Whether re-rolling the player should re-roll the team with it.
     *
     * <p>At quarterback the team is not a real second choice. Most clubs carry exactly one
     * eligible starter, so respinning the player inside a fixed team hands the same man
     * straight back and charges you for the privilege. Running back and flex have several
     * candidates per club, and holding the team while you change the player is the entire
     * reason the two respins are separate.
     */
    public boolean playerRespinTakesTeam() {
        return this == QB;
    }

    public boolean accepts(Player player) {
        return player.position() != null && positions.contains(player.position());
    }

    /** Looks across both modes, so an entry drafted in hard mode still resolves its picks. */
    public Optional<StatOption> option(String key) {
        return Stream.concat(options.stream(), Stream.of(penalty))
                .filter(o -> o.key().equalsIgnoreCase(key))
                .findFirst();
    }
}
