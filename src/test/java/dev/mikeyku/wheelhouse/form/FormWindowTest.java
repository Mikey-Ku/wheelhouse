package dev.mikeyku.wheelhouse.form;

import dev.mikeyku.wheelhouse.espn.EspnClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one boundary that cannot be allowed to slip.
 *
 * <p>ESPN's game log returns a player's whole season, which for an archived contest includes the
 * week being drafted and every week after it. Serving any of that hands the player the answer
 * mid-draft and there is no error, no exception, and nothing on screen to notice: the bars just
 * quietly know the future. So the filter is asserted rather than trusted.
 */
class FormWindowTest {

    /** A season where the player appeared in weeks 1 through 6, with week 4 missed. */
    private static final String GAMELOG = """
        {
          "names": ["passingYards", "passingTouchdowns"],
          "events": {
            "e1": {"week": 1, "atVs": "vs", "gameResult": "W", "opponent": {"abbreviation": "BUF"}},
            "e2": {"week": 2, "atVs": "@",  "gameResult": "L", "opponent": {"abbreviation": "NYJ"}},
            "e3": {"week": 3, "atVs": "vs", "gameResult": "W", "opponent": {"abbreviation": "MIA"}},
            "e5": {"week": 5, "atVs": "@",  "gameResult": "W", "opponent": {"abbreviation": "NE"}},
            "e6": {"week": 6, "atVs": "vs", "gameResult": "L", "opponent": {"abbreviation": "KC"}},
            "p1": {"week": 1, "atVs": "vs", "gameResult": "W", "opponent": {"abbreviation": "BAL"}}
          },
          "seasonTypes": [
            {"displayName": "2024 Regular Season", "categories": [{"events": [
              {"eventId": "e1", "stats": ["210", "1"]},
              {"eventId": "e2", "stats": ["180", "0"]},
              {"eventId": "e3", "stats": ["300", "3"]},
              {"eventId": "e5", "stats": ["250", "2"]},
              {"eventId": "e6", "stats": ["275", "1"]}
            ]}]},
            {"displayName": "2024 Postseason", "categories": [{"events": [
              {"eventId": "p1", "stats": ["999", "9"]}
            ]}]}
          ]
        }
        """;

    /** The log above as 2024, and nothing for any other season. */
    private FormService serviceReturning(String json) {
        return serviceReturning(json, "{}");
    }

    /** The log above as 2024, and {@code lastSeason} as 2023. */
    private FormService serviceReturning(String json, String lastSeason) {
        return new FormService(new EspnClient("test-agent") {
            @Override
            public tools.jackson.databind.JsonNode gamelog(String athleteId, int season) {
                return new ObjectMapper().readTree(season == 2024 ? json
                        : season == 2023 ? lastSeason : "{}");
            }
        }, 400);
    }

    @Test
    void neverReturnsTheWeekBeingDraftedOrAnythingAfterIt() {
        List<FormService.Game> before = serviceReturning(GAMELOG).before("1", 2024, 3);

        assertThat(before).extracting(FormService.Game::week)
                .as("drafting week 3 may only see weeks 1 and 2")
                .containsExactly(1, 2);
    }

    @Test
    void weekOneWithNoPreviousSeasonHasNothingBehindIt() {
        assertThat(serviceReturning(GAMELOG).before("1", 2024, 1)).isEmpty();
    }

    @Test
    void weekOneReadsTheEndOfLastSeason() {
        // The same log served as 2023: weeks 1, 2, 3, 5, 6 of the previous year.
        List<FormService.Game> before = serviceReturning(GAMELOG, GAMELOG).before("1", 2024, 1);

        assertThat(before).extracting(FormService.Game::season).containsOnly(2023);
        assertThat(before).extracting(FormService.Game::week).containsExactly(1, 2, 3, 5, 6);
    }

    @Test
    void anEarlyWeekIsToppedUpFromLastSeasonOldestFirst() {
        List<FormService.Game> before = serviceReturning(GAMELOG, GAMELOG).before("1", 2024, 3);

        // Four from the end of 2023, then this season's weeks 1 and 2, and never week 3.
        assertThat(before).extracting(g -> g.season() + "-" + g.week())
                .containsExactly("2023-2", "2023-3", "2023-5", "2023-6", "2024-1", "2024-2");
    }

    @Test
    void aMissedWeekLeavesAGapRatherThanShiftingEverythingUp() {
        // Filtering on the week number rather than counting back from the end is what keeps a
        // bye from silently pulling a later game into the window.
        assertThat(serviceReturning(GAMELOG).before("1", 2024, 7))
                .extracting(FormService.Game::week)
                .containsExactly(1, 2, 3, 5, 6);
    }

    @Test
    void postseasonWeeksNeverLeakIntoARegularSeasonWindow() {
        // Both seasons number their weeks from one, so an unfiltered read would put a playoff
        // game in as though it were September.
        List<FormService.Game> games = serviceReturning(GAMELOG).before("1", 2024, 18);
        assertThat(games).extracting(FormService.Game::opponent).doesNotContain("BAL");
        assertThat(games).allSatisfy(g ->
                assertThat(g.stats().getOrDefault("passingYards", 0.0)).isNotEqualTo(999.0));
    }

    @Test
    void theWindowIsCappedAtSixGames() {
        assertThat(serviceReturning(GAMELOG).before("1", 2024, 18)).hasSizeLessThanOrEqualTo(6);
    }

    @Test
    void aMissingGameLogIsEmptyRatherThanAnException() {
        FormService failing = new FormService(new EspnClient("test-agent") {
            @Override
            public tools.jackson.databind.JsonNode gamelog(String athleteId, int season) {
                throw new IllegalStateException("ESPN is down");
            }
        }, 400);
        assertThat(failing.before("1", 2024, 8)).isEmpty();
    }

    @Test
    void aPlayerWithNoIdHasNoForm() {
        assertThat(serviceReturning(GAMELOG).before(null, 2024, 8)).isEmpty();
        assertThat(serviceReturning(GAMELOG).before("  ", 2024, 8)).isEmpty();
    }
}
