package dev.mikeyku.wheelhouse.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A whole draft for each kind of slate in the week ESPN is currently serving.
 *
 * <p>Two promises are pinned. A slate's wheel never lands outside its own games, because by
 * Sunday the Thursday results are public. And a showdown can actually be filled from one game,
 * which a classic roster cannot: it needs four different quarterbacks and a game has two.
 *
 * <p>The lock is off so this runs on any day of the week, including after the slates it drafts
 * have kicked off.
 */
@Tag("network")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:slates;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "wheelhouse.contest.enforce-lock=false"
})
class SlateDraftTest {

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
    void everySlateDraftsToCompletionInsideItsOwnGames() throws Exception {
        JsonNode slates = call("GET", "/api/play/slates").path("slates");
        assertThat(slates.size()).as("the current week has slates").isPositive();

        for (JsonNode slate : slates) {
            Set<String> playing = new HashSet<>();
            for (JsonNode game : slate.path("games")) {
                playing.add(game.path("away").asText());
                playing.add(game.path("home").asText());
            }
            JsonNode view = draft(slate.path("key").asText());
            String label = slate.path("label").asText();

            assertThat(view.path("complete").asBoolean()).as(label + " completes").isTrue();
            assertThat(view.path("totalPicks").asInt()).isEqualTo(slate.path("picks").asInt());

            Set<String> players = new HashSet<>();
            for (JsonNode position : view.path("positions")) {
                for (JsonNode pick : position.path("picks")) {
                    assertThat(playing).as(label + " pick " + pick.path("pickIndex"))
                            .contains(pick.path("team").asText());
                    assertThat(players.add(pick.path("player").path("id").asText()))
                            .as("nobody twice on one roster").isTrue();
                    assertThat(pick.path("opponent").asText()).as("opponent known before kickoff")
                            .isNotBlank();
                }
            }

            JsonNode board = call("GET", "/api/play/leaderboard?slate=" + slate.path("key").asText());
            assertThat(board.size()).as(label + " board lists the finished roster").isEqualTo(1);
            assertThat(board.get(0).has("entryId")).isFalse();
        }
    }

    private JsonNode draft(String slate) throws Exception {
        JsonNode view = call("POST", "/api/play/open?owner=tester&slate=" + slate);
        String id = view.path("entryId").asText();
        assertThat(id).as("opened " + slate + ": " + view.path("error")).isNotBlank();
        while (!view.path("complete").asBoolean()) {
            int i = view.path("activePick").asInt();
            view = call("POST", "/api/play/" + id + "/pick/" + i + "/spin");
            assertThat(view.has("error")).as(slate + " spin " + i + ": " + view.path("error")).isFalse();
            JsonNode pick = null;
            for (JsonNode position : view.path("positions")) {
                for (JsonNode p : position.path("picks")) {
                    if (p.path("pickIndex").asInt() == i) {
                        pick = p;
                    }
                }
            }
            String option = pick.path("options").path(0).path("key").asText();
            view = call("POST", "/api/play/" + id + "/pick/" + i + "/choose?option=" + option);
            assertThat(view.has("error")).as(slate + " choose " + i + ": " + view.path("error")).isFalse();
        }
        return view;
    }
}
