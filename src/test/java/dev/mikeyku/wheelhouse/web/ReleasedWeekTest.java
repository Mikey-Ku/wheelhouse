package dev.mikeyku.wheelhouse.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A draft outlives the week it is drafting being held in memory.
 *
 * <p>Found by a load run: with more archived weeks in play than the cap holds, a week was
 * released between two picks of an entry still being built, and the next spin answered "no team
 * has an eligible QB left" because the player pool had gone with it. Only the read endpoint
 * reloaded a released week; the ones that act on an entry did not.
 *
 * <p>A cap of one makes the release certain rather than a matter of timing.
 */
@Tag("network")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:released;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "wheelhouse.archive.max-loaded=1"
})
class ReleasedWeekTest {

    @Autowired
    private WebApplicationContext context;

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode post(String path) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        return mapper.readTree(mvc.perform(MockMvcRequestBuilders.post(path))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void aSpinOnAReleasedWeekBringsTheWeekBack() throws Exception {
        String first = post("/api/play/open?owner=a&season=2023&week=9").path("entryId").asText();
        // Opening a second week with a cap of one releases the first.
        post("/api/play/open?owner=b&season=2023&week=10");

        JsonNode view = post("/api/play/" + first + "/pick/0/spin");

        assertThat(view.has("error")).as("spin on a released week: " + view.path("error")).isFalse();
        JsonNode pick = view.path("positions").path(0).path("picks").path(0);
        assertThat(pick.path("player").path("name").asText()).isNotBlank();
        assertThat(pick.path("options").size()).isPositive();
    }
}
