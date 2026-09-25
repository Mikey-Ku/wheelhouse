package dev.mikeyku.wheelhouse.web;

import dev.mikeyku.wheelhouse.account.Identities;
import dev.mikeyku.wheelhouse.account.SignInRequired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Supabase, for tests. A token is {@code test:<person>:<name>}: the person is the Supabase user
 * id, the name is what Google or the sign-up form would have offered. Anything else is a token
 * Supabase would refuse.
 *
 * <p>A component in the test tree, so every {@code @SpringBootTest} context scans it in and it
 * wins over the real client, which would otherwise call out to a project the tests do not have.
 */
@Component
@Primary
public class FakeIdentities implements Identities {

    static String token(String person, String name) {
        return "test:" + person + ":" + name;
    }

    @Override
    public Identity verify(String accessToken) {
        if (accessToken == null || !accessToken.startsWith("test:")) {
            throw new SignInRequired("That sign-in expired. Try again.");
        }
        String[] parts = accessToken.split(":", 3);
        return new Identity(parts[1], parts.length > 2 ? parts[2] : "");
    }
}
