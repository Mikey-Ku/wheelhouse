package dev.mikeyku.wheelhouse.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A shared slip is read-only. The entry id opens a draft for writing, so the link posted to a
 * group chat carries a separate token, and nothing the link returns can be used to act.
 */
@Tag("network")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shared;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SharedSlipTest {

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
    void aSharedSlipCarriesNoWayBackIntoTheDraft() throws Exception {
        JsonNode view = call("POST", "/api/play/open?owner=tester&season=2023&week=9");
        String id = view.path("entryId").asText();

        assertThat(call("POST", "/api/play/" + id + "/share").has("error"))
                .as("an unfinished roster cannot be shared").isTrue();

        while (!view.path("complete").asBoolean()) {
            int i = view.path("activePick").asInt();
            view = call("POST", "/api/play/" + id + "/pick/" + i + "/spin");
            JsonNode pick = null;
            for (JsonNode position : view.path("positions")) {
                for (JsonNode p : position.path("picks")) {
                    if (p.path("pickIndex").asInt() == i) {
                        pick = p;
                    }
                }
            }
            view = call("POST", "/api/play/" + id + "/pick/" + i + "/choose?option="
                    + pick.path("options").path(0).path("key").asText());
        }

        String share = call("POST", "/api/play/" + id + "/share").path("shareId").asText();
        assertThat(share).isNotBlank().isNotEqualTo(id);
        assertThat(call("POST", "/api/play/" + id + "/share").path("shareId").asText())
                .as("sharing twice gives the same link").isEqualTo(share);

        JsonNode shared = call("GET", "/api/play/shared/" + share);
        assertThat(shared.path("complete").asBoolean()).isTrue();
        assertThat(shared.toString()).doesNotContain(id);
        assertThat(shared.has("teamRespins")).isFalse();

        // The token is not an entry id anywhere else.
        assertThat(call("POST", "/api/play/" + share + "/pick/0/choose?option=arm").has("error"))
                .isTrue();
        assertThat(call("POST", "/api/play/" + share + "/share").has("error")).isTrue();
    }
}
