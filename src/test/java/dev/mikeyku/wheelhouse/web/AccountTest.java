package dev.mikeyku.wheelhouse.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Profiles: signing in through Supabase, what a name is allowed to be, and signing out. */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:accounts;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "wheelhouse.supabase.url=https://example.supabase.co",
        "wheelhouse.supabase.key=sb_publishable_test"
})
class AccountTest {

    @Autowired
    private WebApplicationContext context;

    @Test
    void theFirstSignInMakesAProfileAndSignsYouIn() throws Exception {
        Api browser = new Api(context);
        assertThat(browser.get("/api/account/me").path("signedIn").asBoolean()).isFalse();

        browser.signUp("Mikey Ku");

        JsonNode me = browser.get("/api/account/me");
        assertThat(me.path("signedIn").asBoolean()).isTrue();
        assertThat(me.path("name").asText()).isEqualTo("Mikey Ku");
    }

    @Test
    void signingInAgainIsTheSameProfileUnderTheNameItChose() throws Exception {
        String person = UUID.randomUUID().toString();
        Api phone = new Api(context);
        phone.signInAs(person, "Dana");
        phone.postJson("/api/account/name", Map.of("name", "Dana R"));

        // Google offers its name on every sign-in; the one they chose is the one that stays.
        assertThat(new Api(context).signInAs(person, "Dana").path("name").asText()).isEqualTo("Dana R");
    }

    @Test
    void aTakenNameGetsTheNextFreeNumber() throws Exception {
        new Api(context).signUp("Casey");
        assertThat(new Api(context).signUp("casey").path("name").asText()).isEqualTo("casey 2");
        assertThat(new Api(context).signUp("Casey").path("name").asText()).isEqualTo("Casey 3");
    }

    @Test
    void anOfferedNameIsCleanedToWhatABoardCanPrint() throws Exception {
        assertThat(new Api(context).signUp("<script>").path("name").asText()).isEqualTo("script");
        assertThat(new Api(context).signUp("Quinn ✨").path("name").asText()).isEqualTo("Quinn");
        assertThat(new Api(context).signUp("q".repeat(30)).path("name").asText()).isEqualTo("q".repeat(20));
        assertThat(new Api(context).signUp("!").path("name").asText()).startsWith("Player");
    }

    @Test
    void theNameCheckSaysTakenBeforeAnAccountExists() throws Exception {
        new Api(context).signUp("Jordan");

        Api browser = new Api(context);
        assertThat(browser.postJson("/api/account/name-check", Map.of("name", "jordan"))
                .path("error").asText()).contains("taken");
        assertThat(browser.lastStatus()).isEqualTo(400);
        assertThat(browser.postJson("/api/account/name-check", Map.of("name", "x")).has("error"))
                .as("too short").isTrue();
        assertThat(browser.postJson("/api/account/name-check", Map.of("name", " Jordan  B "))
                .path("name").asText()).isEqualTo("Jordan B");
    }

    @Test
    void aTokenSupabaseDoesNotVouchForSignsNobodyIn() throws Exception {
        Api browser = new Api(context);
        browser.postJson("/api/account/supabase", Map.of("accessToken", "forged"));
        assertThat(browser.lastStatus()).isEqualTo(401);
        assertThat(browser.get("/api/account/me").path("signedIn").asBoolean()).isFalse();
    }

    @Test
    void thePageIsToldWhichSupabaseProjectToSignInWith() throws Exception {
        JsonNode config = new Api(context).get("/api/account/auth-config");
        assertThat(config.path("url").asText()).isEqualTo("https://example.supabase.co");
        assertThat(config.path("key").asText()).isEqualTo("sb_publishable_test");
    }

    @Test
    void signingOutForgetsYou() throws Exception {
        Api browser = new Api(context);
        browser.signUp("Taylor");
        browser.post("/api/account/signout");
        // The old cookie is still being sent; the session behind it is gone.
        assertThat(browser.get("/api/account/me").path("signedIn").asBoolean()).isFalse();
    }

    @Test
    void renameKeepsNamesUnique() throws Exception {
        new Api(context).signUp("Morgan");
        Api browser = new Api(context);
        browser.signUp("Avery");

        assertThat(browser.postJson("/api/account/name", Map.of("name", "morgan")).has("error")).isTrue();
        assertThat(browser.postJson("/api/account/name", Map.of("name", "<script>")).has("error")).isTrue();
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
