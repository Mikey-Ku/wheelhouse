package dev.mikeyku.wheelhouse.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Asks Supabase who a token belongs to, by calling its user endpoint with the token.
 *
 * <p>Supabase checks the signature, the expiry and whether the session was revoked, so none of
 * that is reimplemented here and no signing secret lives on this server. It costs one request per
 * sign-in, not per page: after this the app's own session cookie carries the person.
 */
@Component
public class SupabaseIdentities implements Identities {

    private static final Logger log = LoggerFactory.getLogger(SupabaseIdentities.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;
    private final String key;

    public SupabaseIdentities(@Value("${wheelhouse.supabase.url:}") String url,
                              @Value("${wheelhouse.supabase.key:}") String key) {
        this.url = url.strip().replaceAll("/+$", "");
        this.key = key.strip();
    }

    @Override
    public Identity verify(String accessToken) {
        if (url.isEmpty() || key.isEmpty()) {
            throw new IllegalStateException("Sign-in isn't set up on this server yet.");
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new SignInRequired("Sign in again.");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/auth/v1/user"))
                .timeout(Duration.ofSeconds(10))
                .header("apikey", key)
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("Supabase user lookup failed: {}", e.toString());
            throw new IllegalStateException("Couldn't reach sign-in. Try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Couldn't reach sign-in. Try again.");
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new SignInRequired("That sign-in expired. Try again.");
        }
        if (status != 200) {
            // The body can echo the request; the status is enough to find it in Supabase's logs.
            log.warn("Supabase user lookup answered {}", status);
            throw new IllegalStateException("Couldn't reach sign-in. Try again.");
        }
        JsonNode user = mapper.readTree(response.body());
        String id = user.path("id").asText("");
        if (id.isBlank()) {
            throw new SignInRequired("That sign-in expired. Try again.");
        }
        return new Identity(id, suggestedName(user));
    }

    /**
     * An email sign-up typed the name it wants, so that is used as it is. Google hands over a full
     * name, and a leaderboard is public, so it is shortened to a first name and an initial. Either
     * can be changed on the profile page.
     */
    static String suggestedName(JsonNode user) {
        JsonNode meta = user.path("user_metadata");
        if ("email".equals(user.path("app_metadata").path("provider").asText(""))) {
            return meta.path("name").asText("");
        }
        String full = meta.path("full_name").asText(meta.path("name").asText("")).strip();
        String[] words = full.split("\\s+");
        if (words.length < 2) {
            return full;
        }
        return words[0] + " " + words[words.length - 1].charAt(0) + ".";
    }
}
