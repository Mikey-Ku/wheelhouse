package dev.mikeyku.wheelhouse.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The roster shape, and the rule that every part has to be a stat somebody actually scores.
 *
 * <p>Three parts have already been cut for failing that rule, each time after the weight had
 * been invented rather than inherited and nobody could check it. The point of pinning it here
 * is that the fourth one gets caught before it ships rather than after.
 */
class SlotTest {

    /** Everything standard PPR pays for. Anything outside this set needs a made-up number. */
    private static final Set<String> SCORED_BY_PPR = Set.of(
            "passingYards", "passingTouchdowns", "interceptions",
            "rushingYards", "rushingTouchdowns",
            "receptions", "receivingYards", "receivingTouchdowns",
            "fumblesLost");

    @Test
    void everyPartIsAStatRealFantasyLeaguesScore() {
        for (Slot slot : Slot.values()) {
            for (Slot.StatOption option : slot.options()) {
                for (Slot.StatRef ref : option.stats()) {
                    assertThat(SCORED_BY_PPR)
                            .as("%s.%s harvests %s, which standard PPR does not pay for, so its "
                                    + "multiplier would have to be invented",
                                    slot, option.key(), ref.stat())
                            .contains(ref.stat());
                }
            }
        }
    }

    @Test
    void aPartCannotAppearTwiceInTheSamePosition() {
        for (Slot slot : Slot.values()) {
            List<String> keys = slot.options().stream().map(Slot.StatOption::key).toList();
            assertThat(keys).as("%s", slot).doesNotHaveDuplicates();
        }
    }

    @Test
    void rosterIsFourteenPicksAcrossFourPositions() {
        assertThat(Roster.POSITIONS).hasSize(4);
        assertThat(Roster.TOTAL_PICKS).isEqualTo(14);
    }

    @Test
    void everyPickIndexResolvesToExactlyOnePosition() {
        // positionOf walks a flat index because the positions are no longer equal in size.
        // An off-by-one here would silently draft the wrong slot for a whole position.
        List<Integer> resolved = java.util.stream.IntStream.range(0, Roster.TOTAL_PICKS)
                .mapToObj(Roster::positionOf).toList();

        assertThat(resolved).isSorted();
        assertThat(resolved.stream().collect(Collectors.groupingBy(p -> p, Collectors.counting())))
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                        0, 4L, 1, 4L, 2, 3L, 3, 3L));
    }

    @Test
    void pickIndexPastTheEndIsRejectedRatherThanClamped() {
        assertThat(java.util.stream.IntStream.of(Roster.TOTAL_PICKS, Roster.TOTAL_PICKS + 5))
                .allSatisfy(i -> {
                    try {
                        Roster.positionOf(i);
                        org.junit.jupiter.api.Assertions.fail("index " + i + " should not resolve");
                    } catch (IllegalArgumentException expected) {
                        // the point
                    }
                });
    }

    @Test
    void totalTouchdownsSumsBothWaysOfScoringOne() {
        Slot.StatOption nose = Slot.RB.option("nose").orElseThrow();
        assertThat(nose.stats()).extracting(Slot.StatRef::stat)
                .containsExactlyInAnyOrder("rushingTouchdowns", "receivingTouchdowns");
        assertThat(nose.projectionStats()).containsExactlyInAnyOrder("rush_td", "rec_td");
    }

    @Test
    void everyPartCarriesAUnitSoARawNumberIsNeverUnlabelled() {
        for (Slot slot : Slot.values()) {
            for (Slot.StatOption option : slot.options()) {
                assertThat(option.unit()).as("%s.%s", slot, option.key()).isNotBlank();
            }
        }
    }

    @Test
    void onlyQuarterbackTakesTheTeamWithAPlayerRespin() {
        // Most clubs carry one eligible starter, so holding the team at QB hands the same man
        // back and charges for it. Everywhere else holding the team is the entire mechanic.
        assertThat(Slot.QB.playerRespinTakesTeam()).isTrue();
        assertThat(Slot.RB.playerRespinTakesTeam()).isFalse();
        assertThat(Slot.FLEX.playerRespinTakesTeam()).isFalse();
    }
}
