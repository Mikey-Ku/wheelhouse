package dev.mikeyku.wheelhouse.web;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The two promises the API makes about what it will not say.
 *
 * <p>A blind draft that ships the answers in the same payload is theatre, and the page cannot be
 * trusted to hide them: anyone can open the network tab. Both promises have already been broken
 * in production code by different routes, one through an ad-hoc scoring endpoint and one through
 * the leaderboard, so they are pinned here rather than reasoned about.
 *
 * <p>Runs against an in-memory database so a test never touches the real one.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:withholding;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class WithholdingTest {

    @Autowired
    private WebApplicationContext context;

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode call(String method, String path) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String body = (method.equals("POST")
                ? mvc.perform(post(path))
                : mvc.perform(get(path)))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    @Test
    void anUnfinishedRosterCarriesNoResultsAtAll() throws Exception {
        JsonNode entry = call("POST", "/api/play/open?owner=tester&season=2023&week=9");
        String id = entry.path("entryId").asText();
        call("POST", "/api/play/" + id + "/pick/0/spin");

        JsonNode view = call("GET", "/api/play/" + id);

        assertThat(view.path("revealed").asBoolean()).isFalse();
        assertThat(view.has("total")).as("no total key while building").isFalse();

        for (JsonNode position : view.path("positions")) {
            assertThat(position.has("total")).as("no position total while building").isFalse();
            for (JsonNode pick : position.path("picks")) {
                assertThat(pick.has("points")).as("no pick points while building").isFalse();
                assertThat(pick.has("raw")).as("no pick raw while building").isFalse();
                for (JsonNode option : pick.path("options")) {
                    assertThat(option.has("points")).as("no option points while building").isFalse();
                    assertThat(option.has("raw")).as("no option raw while building").isFalse();
                }
            }
        }
    }

    @Test
    void theLeaderboardPublishesNoEntryIds() throws Exception {
        // An entry id is a bearer token: every pick endpoint accepts one on its own. Names are
        // printed on this board, so a board that also prints ids makes a name a password.
        JsonNode entry = call("POST", "/api/play/open?owner=tester&season=2023&week=9");
        String contestId = entry.path("contest").path("id").asText();

        JsonNode board = call("GET", "/api/play/leaderboard?contestId=" + contestId);

        assertThat(board.isArray()).isTrue();
        assertThat(board).isNotEmpty();
        for (JsonNode row : board) {
            assertThat(row.has("entryId")).as("leaderboard row must not carry an entry id").isFalse();
            assertThat(row.has("owner")).isTrue();
        }
    }

    @Test
    void thereIsNoWayToLookAnEntryUpByName() throws Exception {
        // The old /history?owner= is gone. This asserts the path does not resolve to a list of
        // somebody's entries; it falls through to the entry lookup and finds nothing.
        call("POST", "/api/play/open?owner=victim&season=2023&week=9");

        JsonNode response = call("GET", "/api/play/history?owner=victim");

        assertThat(response.isArray())
                .as("a name must not return a list of entries")
                .isFalse();
    }

    @Test
    void anIncompleteEntryScoresZeroOnTheBoardRatherThanShowingItsRealTotal() throws Exception {
        JsonNode entry = call("POST", "/api/play/open?owner=partial&season=2023&week=9");
        String id = entry.path("entryId").asText();
        String contestId = entry.path("contest").path("id").asText();
        call("POST", "/api/play/" + id + "/pick/0/spin");

        JsonNode board = call("GET", "/api/play/leaderboard?contestId=" + contestId);
        for (JsonNode row : board) {
            if (!row.path("complete").asBoolean()) {
                assertThat(row.path("total").asDouble()).isEqualTo(0.0);
            }
        }
    }
}
