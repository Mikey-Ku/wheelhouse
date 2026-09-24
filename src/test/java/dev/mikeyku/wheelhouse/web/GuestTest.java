package dev.mikeyku.wheelhouse.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Playing as a guest: allowed, kept, and off the leaderboards until the guest makes a profile
 * or signs in to one, at which point everything they played comes with them.
 */
@Tag("network")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:guests;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class GuestTest {

    @Autowired
    private WebApplicationContext context;

    private static String name() {
        return "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** Opens and finishes a past-week roster, taking the first stat every time. */
    private static JsonNode finish(Api api) throws Exception {
        JsonNode view = api.post("/api/play/open?season=2023&week=9");
        String id = view.path("entryId").asText();
        while (!view.path("complete").asBoolean()) {
            int i = view.path("activePick").asInt();
            view = api.post("/api/play/" + id + "/pick/" + i + "/spin");
            String option = null;
            for (JsonNode position : view.path("positions")) {
                for (JsonNode pick : position.path("picks")) {
                    if (pick.path("pickIndex").asInt() == i) {
                        option = pick.path("options").get(0).path("key").asText();
                    }
                }
            }
            view = api.post("/api/play/" + id + "/pick/" + i + "/choose?option=" + option);
        }
        return view;
    }

    private static boolean onBoard(Api api, String contestId, String owner) throws Exception {
        for (JsonNode row : api.get("/api/play/leaderboard?contestId=" + contestId)) {
            if (row.path("owner").asText().equals(owner)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aGuestPlaysButStaysOffTheBoardUntilTheyMakeAProfile() throws Exception {
        Api guest = new Api(context);
        JsonNode me = guest.post("/api/account/guest");
        assertThat(me.path("guest").asBoolean()).isTrue();
        assertThat(me.path("signedIn").asBoolean()).as("a guest has no profile").isFalse();

        JsonNode done = finish(guest);
        String contestId = done.path("contest").path("id").asText();
        assertThat(done.path("guest").asBoolean()).isTrue();
        assertThat(done.path("standing").isNull() || done.path("standing").isMissingNode()).isTrue();
        assertThat(onBoard(guest, contestId, done.path("owner").asText())).isFalse();

        String profile = name();
        guest.signUp(profile, "hunter22");

        assertThat(onBoard(guest, contestId, profile)).as("the roster came with the new profile").isTrue();
        assertThat(guest.get("/api/play/" + done.path("entryId").asText()).path("owner").asText())
                .isEqualTo(profile);
    }

    @Test
    void signingInFromAGuestSessionBringsTheGuestsRosters() throws Exception {
        String profile = name();
        new Api(context).signUp(profile, "hunter22");

        Api guest = new Api(context);
        guest.post("/api/account/guest");
        String id = finish(guest).path("entryId").asText();

        guest.signIn(profile, "hunter22");

        JsonNode rosters = guest.get("/api/account/rosters");
        assertThat(rosters.path("name").asText()).isEqualTo(profile);
        assertThat(rosters.path("rosters").toString()).contains(id);
    }

    @Test
    void aGuestCannotTakeANameWithoutAProfile() throws Exception {
        Api guest = new Api(context);
        guest.post("/api/account/guest");
        guest.postJson("/api/account/name", Map.of("name", "Sneaky"));
        assertThat(guest.lastStatus()).isEqualTo(401);
    }

    @Test
    void askingForAGuestTwiceKeepsTheFirst() throws Exception {
        Api guest = new Api(context);
        String first = guest.post("/api/account/guest").path("name").asText();
        assertThat(guest.post("/api/account/guest").path("name").asText()).isEqualTo(first);
    }
}
