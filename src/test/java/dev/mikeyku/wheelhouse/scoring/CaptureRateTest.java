package dev.mikeyku.wheelhouse.scoring;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Capture rate, driven through a whole real draft.
 *
 * <p>The invariants are cheap to state and expensive to get wrong. A ceiling below the score
 * means the assignment search missed the arrangement the player actually used, which would
 * report better-than-perfect play. A ceiling of zero means the week never loaded, and reporting
 * a percentage there congratulates somebody for a scoreboard the server failed to fetch.
 *
 * <p>Tagged {@code network}: this replays a real archived week, so it needs ESPN and Sleeper.
 * Run the rest with {@code -DexcludedGroups=network}.
 */
@Tag("network")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:capture;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class CaptureRateTest {

    @Autowired
    private WebApplicationContext context;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    private JsonNode send(String path) throws Exception {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        return mapper.readTree(
                mvc.perform(post(path)).andReturn().getResponse().getContentAsString());
    }

    /** Drafts a complete roster by always taking the first option offered. */
    private JsonNode completeRoster() throws Exception {
        JsonNode entry = send("/api/play/open?owner=capture&season=2023&week=9");
        String id = entry.path("entryId").asText();
        int total = entry.path("totalPicks").asInt();

        JsonNode view = entry;
        for (int i = 0; i < total; i++) {
            view = send("/api/play/" + id + "/pick/" + i + "/spin");
            String option = null;
            for (JsonNode position : view.path("positions")) {
                for (JsonNode pick : position.path("picks")) {
                    if (pick.path("pickIndex").asInt() == i) {
                        option = pick.path("options").get(0).path("key").asText();
                    }
                }
            }
            view = send("/api/play/" + id + "/pick/" + i + "/choose?option=" + option);
        }
        return view;
    }

    @Test
    void aFinishedRosterReportsWhatItLeftOnTheTable() throws Exception {
        JsonNode view = completeRoster();
        assertThat(view.path("complete").asBoolean()).isTrue();

        JsonNode capture = view.path("capture");
        assertThat(capture.isMissingNode()).as("a complete roster carries a capture rate").isFalse();

        double scored = capture.path("scored").asDouble();
        double ceiling = capture.path("ceiling").asDouble();
        int percent = capture.path("capturePercent").asInt();

        assertThat(ceiling).as("the board was worth something").isGreaterThan(0);
        assertThat(scored).as("you cannot beat the best possible use of your own players")
                .isLessThanOrEqualTo(ceiling + 0.01);
        assertThat(percent).isBetween(0, 100);
        assertThat(percent).isCloseTo((int) Math.round(100.0 * scored / ceiling),
                org.assertj.core.data.Offset.offset(1));
    }

    @Test
    void theArrangementThatReachesTheCeilingIsNeverReported() throws Exception {
        // A week can be replayed. Printing the answer on the way out turns the second run into
        // a copying exercise, so the number ships and the assignment does not.
        JsonNode capture = completeRoster().path("capture");
        assertThat(capture.has("worstCall")).isFalse();
        assertThat(capture.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("scored", "ceiling", "capturePercent");
    }
}
