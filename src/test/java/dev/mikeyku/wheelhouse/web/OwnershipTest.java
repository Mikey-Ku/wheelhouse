package dev.mikeyku.wheelhouse.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A roster is its owner's. Its id is in the address bar, so the id alone must not be enough to
 * read it, spin it, or share it. And a made pick is final.
 */
@Tag("network")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ownership;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class OwnershipTest {

    @Autowired
    private WebApplicationContext context;

    @Test
    void someoneElseCannotTouchYourRoster() throws Exception {
        Api owner = Api.signedUp(context);
        String id = owner.post("/api/play/open?season=2023&week=9").path("entryId").asText();

        Api stranger = Api.signedUp(context);
        stranger.get("/api/play/" + id);
        assertThat(stranger.lastStatus()).isEqualTo(403);
        stranger.post("/api/play/" + id + "/pick/0/spin");
        assertThat(stranger.lastStatus()).isEqualTo(403);

        Api anonymous = new Api(context);
        anonymous.get("/api/play/" + id);
        assertThat(anonymous.lastStatus()).isEqualTo(403);

        assertThat(owner.get("/api/play/" + id).path("entryId").asText()).isEqualTo(id);
    }

    @Test
    void aMadePickCannotBeRerolledOrReassigned() throws Exception {
        Api api = Api.signedUp(context);
        String id = api.post("/api/play/open?season=2023&week=9").path("entryId").asText();
        JsonNode view = api.post("/api/play/" + id + "/pick/0/spin");
        String first = view.path("positions").get(0).path("picks").get(0)
                .path("options").get(0).path("key").asText();
        String second = view.path("positions").get(0).path("picks").get(0)
                .path("options").get(1).path("key").asText();
        api.post("/api/play/" + id + "/pick/0/choose?option=" + first);

        assertThat(api.post("/api/play/" + id + "/pick/0/choose?option=" + second)
                .path("error").asText()).contains("already made");
        assertThat(api.post("/api/play/" + id + "/pick/0/spin?respinTeam=true")
                .path("error").asText()).contains("already made");
        assertThat(api.post("/api/play/" + id + "/pick/3/spin")
                .path("error").asText()).contains("current pick");
    }

    @Test
    void yourRostersAreOnYourProfileUnderYourName() throws Exception {
        Api api = Api.signedUp(context);
        String id = api.post("/api/play/open?season=2023&week=9").path("entryId").asText();
        api.postJson("/api/account/name", Map.of("name", "Renamed Later"));

        JsonNode rosters = api.get("/api/account/rosters");
        assertThat(rosters.path("rosters").get(0).path("entryId").asText()).isEqualTo(id);
        assertThat(api.get("/api/play/" + id).path("owner").asText()).isEqualTo("Renamed Later");
    }
}
