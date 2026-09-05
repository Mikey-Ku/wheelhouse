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

    private FormService serviceReturning(String json) {
        return new FormService(new EspnClient("test-agent") {
            @Override
            public tools.jackson.databind.JsonNode gamelog(String athleteId, int season) {
                return new ObjectMapper().readTree(json);
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
    void weekOneHasNothingBehindIt() {
        assertThat(serviceReturning(GAMELOG).before("1", 2024, 1)).isEmpty();
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
