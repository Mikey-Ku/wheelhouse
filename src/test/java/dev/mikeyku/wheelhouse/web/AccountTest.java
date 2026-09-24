package dev.mikeyku.wheelhouse.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Profiles: making one, signing in to it, and what a name is allowed to be. */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:accounts;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AccountTest {

    @Autowired
    private WebApplicationContext context;

    @Test
    void signUpSignsYouIn() throws Exception {
        Api browser = new Api(context);
        assertThat(browser.get("/api/account/me").path("signedIn").asBoolean()).isFalse();

        browser.signUp("Mikey Ku", "hunter22");

        JsonNode me = browser.get("/api/account/me");
        assertThat(me.path("signedIn").asBoolean()).isTrue();
        assertThat(me.path("name").asText()).isEqualTo("Mikey Ku");
    }

    @Test
    void aNameIsOneProfileWhateverItsCase() throws Exception {
        new Api(context).signUp("Casey", "hunter22");

        Api other = new Api(context);
        assertThat(other.signUp("casey", "different1").path("error").asText()).contains("taken");
        assertThat(other.lastStatus()).isEqualTo(400);
    }

    @Test
    void theWrongPasswordSaysNothingAboutWhichHalfWasWrong() throws Exception {
        new Api(context).signUp("Jordan", "hunter22");

        Api browser = new Api(context);
        String wrongPassword = browser.signIn("Jordan", "nope-nope").path("error").asText();
        String noSuchName = browser.signIn("Nobody", "nope-nope").path("error").asText();
        assertThat(wrongPassword).isEqualTo(noSuchName);

        assertThat(browser.signIn("jordan", "hunter22").path("name").asText()).isEqualTo("Jordan");
    }

    @Test
    void namesAndPasswordsHaveLimits() throws Exception {
        Api browser = new Api(context);
        assertThat(browser.signUp("x", "hunter22").has("error")).as("too short").isTrue();
        assertThat(browser.signUp("x".repeat(21), "hunter22").has("error")).as("too long").isTrue();
        assertThat(browser.signUp("<script>", "hunter22").has("error")).as("markup").isTrue();
        assertThat(browser.signUp("Riley", "12345").has("error")).as("short password").isTrue();
    }

    @Test
    void signingOutForgetsYou() throws Exception {
        Api browser = new Api(context);
        browser.signUp("Taylor", "hunter22");
        browser.post("/api/account/signout");
        // The old cookie is still being sent; the session behind it is gone.
        assertThat(browser.get("/api/account/me").path("signedIn").asBoolean()).isFalse();
    }

    @Test
    void renameKeepsNamesUnique() throws Exception {
        new Api(context).signUp("Morgan", "hunter22");
        Api browser = new Api(context);
        browser.signUp("Avery", "hunter22");

        assertThat(browser.postJson("/api/account/name", Map.of("name", "morgan")).has("error")).isTrue();
        assertThat(browser.postJson("/api/account/name", Map.of("name", " Avery  B "))
                .path("name").asText()).isEqualTo("Avery B");
    }

    @Test
    void playingNeedsAProfile() throws Exception {
        Api anonymous = new Api(context);
        anonymous.post("/api/play/open?slate=sun");
        assertThat(anonymous.lastStatus()).isEqualTo(401);
        assertThat(anonymous.get("/api/account/rosters").path("error").asText()).contains("Sign in");
    }
}
