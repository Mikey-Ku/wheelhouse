package dev.mikeyku.wheelhouse.contest;

import dev.mikeyku.wheelhouse.model.Format;
import dev.mikeyku.wheelhouse.model.Slot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a week splits into slates, against the real 2026 week 3 scoreboard: one Thursday game,
 * thirteen on Sunday afternoon plus Sunday night, one on Monday.
 */
class SlateTest {

    private static List<Slate> week3() throws Exception {
        Path fixture = Path.of("src/test/resources/fixtures/scoreboard-2026-w3.json");
        JsonNode board = new ObjectMapper().readTree(Files.readString(fixture));
        return Slate.from(board);
    }

    @Test
    void aWeekSplitsIntoThursdaySundayAndMonday() throws Exception {
        List<Slate> slates = week3();

        assertThat(slates).extracting(Slate::key).containsExactly("thu", "sun", "mon");
        assertThat(slates).extracting(Slate::label)
                .containsExactly("Thursday Night", "Sunday", "Monday Night");
        assertThat(slates).extracting(Slate::format)
                .containsExactly(Format.SHOWDOWN, Format.CLASSIC, Format.SHOWDOWN);
        assertThat(slates).extracting(s -> s.games().size()).containsExactly(1, 14, 1);
    }

    @Test
    void eachSlateLocksAtItsOwnFirstKickoff() throws Exception {
        List<Slate> slates = week3();

        // 8:15pm Eastern Thursday, 1pm Sunday, 8:15pm Monday. The Monday game is Tuesday in UTC
        // and must still be read as Monday.
        assertThat(slates.get(0).lockAt()).isEqualTo(Instant.parse("2026-09-25T00:15:00Z"));
        assertThat(slates.get(1).lockAt()).isEqualTo(Instant.parse("2026-09-27T17:00:00Z"));
        assertThat(slates.get(2).lockAt()).isEqualTo(Instant.parse("2026-09-29T00:15:00Z"));

        // The whole point: Sunday is still open after Thursday has kicked off.
        Instant fridayMorning = Instant.parse("2026-09-25T14:00:00Z");
        assertThat(slates.get(0).locked(fridayMorning)).isTrue();
        assertThat(slates.get(1).locked(fridayMorning)).isFalse();
    }

    @Test
    void sundayNeverOffersATeamThatPlayedThursdayOrPlaysMonday() throws Exception {
        Slate sunday = week3().get(1);

        // By Sunday the Thursday teams' results are public. Offering them would leak answers.
        assertThat(sunday.teams()).doesNotContain("ATL", "GB", "PHI", "CHI").hasSize(28);
    }

    @Test
    void washingtonIsTranslatedToTheCodeTheWheelUses() throws Exception {
        assertThat(week3().get(1).teams()).contains("WAS").doesNotContain("WSH");
    }

    @Test
    void aShowdownIsSevenPicksAndAClassicIsFourteen() {
        assertThat(Format.CLASSIC.totalPicks()).isEqualTo(14);
        assertThat(Format.SHOWDOWN.totalPicks()).isEqualTo(7);
        assertThat(IntStream.range(0, 7).mapToObj(Format.SHOWDOWN::slotForPick).toList())
                .containsExactly(Slot.QB, Slot.QB, Slot.RB, Slot.RB, Slot.FLEX, Slot.FLEX, Slot.FLEX);
    }
}
