package dev.mikeyku.wheelhouse.projection;

import dev.mikeyku.wheelhouse.contest.Contest;
import dev.mikeyku.wheelhouse.sleeper.SleeperClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The difference between "Sleeper said there are no forecasts" and "Sleeper did not answer".
 * The first is a fact about the week and is kept. The second is a fact about the network and
 * must not be, or one bad response at cold start hides the live game until the next restart.
 */
class ProjectionFailureTest {

    private static final String FAIL = "FAIL";
    private static final String ONE_ROW = """
            [{"player_id": "1", "team": "KC",
              "player": {"first_name": "Patrick", "last_name": "Mahomes"},
              "stats": {"pass_yd": 280.5}}]
            """;

    /** Answers each call from the script in order; FAIL throws. */
    private static SleeperClient scripted(AtomicInteger calls, String... script) {
        List<String> answers = List.of(script);
        return new SleeperClient("/tmp/wheelhouse-test-cache", 20) {
            @Override
            public JsonNode projections(int season, int seasonType, int week) throws IOException {
                String answer = answers.get(Math.min(calls.getAndIncrement(), answers.size() - 1));
                if (FAIL.equals(answer)) {
                    throw new IOException("Sleeper returned 503");
                }
                return new ObjectMapper().readTree(answer);
            }
        };
    }

    @Test
    void aFailedFetchIsNotRememberedSoTheNextRefreshRepairsIt() {
        AtomicInteger calls = new AtomicInteger();
        ProjectionService projections = new ProjectionService(scripted(calls, FAIL, ONE_ROW));
        Contest week = Contest.live(2026, 2, 1, null);

        projections.load(week);
        assertThat(projections.known(week.id())).isFalse();
        assertThat(projections.available(week.id())).isFalse();

        projections.load(week);   // what the five-minute contest refresh does
        assertThat(projections.available(week.id())).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    void aConfirmedEmptyWeekIsRememberedAndNotRefetched() {
        AtomicInteger calls = new AtomicInteger();
        ProjectionService projections = new ProjectionService(scripted(calls, "[]"));
        Contest preseason = Contest.live(2026, 1, 2, null);

        projections.load(preseason);
        projections.load(preseason);
        projections.load(preseason);

        assertThat(projections.known(preseason.id())).isTrue();
        assertThat(projections.available(preseason.id())).isFalse();
        assertThat(calls).as("an empty answer is an answer; ask once").hasValue(1);
    }
}
