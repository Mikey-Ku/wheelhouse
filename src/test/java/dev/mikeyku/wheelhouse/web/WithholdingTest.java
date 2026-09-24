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

    private Api api;

    private JsonNode call(String method, String path) throws Exception {
        if (api == null) {
            api = Api.signedUp(context);
        }
        return method.equals("POST") ? api.post(path) : api.get(path);
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
        // An entry id opens a draft. Names are printed on this board, so a board that also
        // printed ids would hand anyone a way in. Your own row may carry yours; nobody else's.
        JsonNode view = call("POST", "/api/play/open?season=2023&week=9");
        String id = view.path("entryId").asText();
        String contestId = view.path("contest").path("id").asText();

        assertThat(call("GET", "/api/play/leaderboard?contestId=" + contestId))
                .as("an unfinished roster is not on the board").isEmpty();

        while (!view.path("complete").asBoolean()) {
            int i = view.path("activePick").asInt();
            view = call("POST", "/api/play/" + id + "/pick/" + i + "/spin");
            String option = null;
            for (JsonNode position : view.path("positions")) {
                for (JsonNode pick : position.path("picks")) {
                    if (pick.path("pickIndex").asInt() == i) {
                        option = pick.path("options").get(0).path("key").asText();
                    }
                }
            }
            view = call("POST", "/api/play/" + id + "/pick/" + i + "/choose?option=" + option);
        }

        JsonNode board = call("GET", "/api/play/leaderboard?contestId=" + contestId);
        assertThat(board).hasSize(1);
        for (JsonNode row : board) {
            assertThat(row.has("entryId")).as("leaderboard row must not carry an entry id").isFalse();
            assertThat(row.has("owner")).isTrue();
        }

        JsonNode stranger = Api.signedUp(context).get("/api/boards/one?contestId=" + contestId);
        assertThat(stranger.path("rows").get(0).path("entryId").isNull()).isTrue();
        JsonNode mine = call("GET", "/api/boards/one?contestId=" + contestId);
        assertThat(mine.path("rows").get(0).path("entryId").asText()).isEqualTo(id);
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
    void anIncompleteEntryIsNotOnTheBoardAtAll() throws Exception {
        // A half-built roster's total is a side channel into results it has not earned yet.
        Api partial = Api.signedUp(context);
        String name = partial.get("/api/account/me").path("name").asText();
        JsonNode entry = partial.post("/api/play/open?season=2023&week=9");
        String contestId = entry.path("contest").path("id").asText();
        partial.post("/api/play/" + entry.path("entryId").asText() + "/pick/0/spin");

        JsonNode board = call("GET", "/api/play/leaderboard?contestId=" + contestId);
        for (JsonNode row : board) {
            assertThat(row.path("owner").asText()).isNotEqualTo(name);
        }
    }
}
