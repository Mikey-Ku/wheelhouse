package dev.mikeyku.wheelhouse.account;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The name a first sign-in is offered, from the user Supabase describes. */
class SupabaseIdentitiesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String suggested(String json) {
        return SupabaseIdentities.suggestedName(mapper.readTree(json));
    }

    @Test
    void googleIsShortenedToAFirstNameAndAnInitial() {
        assertThat(suggested("""
                {"app_metadata":{"provider":"google"},
                 "user_metadata":{"full_name":"Michael Ku Jr","name":"Michael Ku Jr"}}"""))
                .isEqualTo("Michael J.");
        assertThat(suggested("""
                {"app_metadata":{"provider":"google"},"user_metadata":{"name":"Cher"}}"""))
                .isEqualTo("Cher");
    }

    @Test
    void anEmailSignUpKeepsTheNameItTyped() {
        assertThat(suggested("""
                {"app_metadata":{"provider":"email"},"user_metadata":{"name":"Mikey Ku"}}"""))
                .isEqualTo("Mikey Ku");
    }

    @Test
    void nothingOfferedIsBlankForTheServiceToFill() {
        assertThat(suggested("{\"app_metadata\":{\"provider\":\"google\"}}")).isEmpty();
    }

    @Test
    void withoutAProjectItSaysSignInIsNotSetUp() {
        assertThatThrownBy(() -> new SupabaseIdentities("", "").verify("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("isn't set up");
    }
}
