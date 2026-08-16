package dev.mikeyku.wheelhouse.espn;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Reads ESPN's public site API. Undocumented but stable in practice, no key required.
 *
 * <p>Two endpoints do all the work: the scoreboard tells us which games exist and which
 * are in progress, and the summary gives us a full box score for one game.
 */
@Component
public class EspnClient {

    private static final String SCOREBOARD =
            "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard";
    private static final String SUMMARY =
            "https://site.api.espn.com/apis/site/v2/sports/football/nfl/summary?event=";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * ESPN's edge rejects requests whose User-Agent it does not recognise, including
     * Java's default {@code Java-http-client/21}, and also rejects UAs that claim to be a
     * browser without matching browser fingerprints. An honest identifying string with a
     * contact URL passes and is the right thing to send anyway.
     */
    private final String userAgent;

    public EspnClient(@Value("${wheelhouse.espn.user-agent}") String userAgent) {
        this.userAgent = userAgent;
    }

    /** One game as listed on the scoreboard. State is "pre", "in", or "post". */
    public record GameRef(String eventId, String name, String state, String detail) {
        public boolean isLive() {
            return "in".equals(state);
        }
    }

    /** The whole scoreboard payload, for callers that need season and week rather than games. */
    public JsonNode scoreboardRaw() throws IOException, InterruptedException {
        return get(SCOREBOARD);
    }

    /**
     * The scoreboard for a finished week. Verified back to 2001; older seasons return nothing.
     * Historical summaries carry the same box score shape as live ones, so everything
     * downstream is unchanged.
     */
    public List<GameRef> scoreboard(int season, int seasonType, int week)
            throws IOException, InterruptedException {
        JsonNode root = get("%s?dates=%d&seasontype=%d&week=%d"
                .formatted(SCOREBOARD, season, seasonType, week));
        List<GameRef> games = new ArrayList<>();
        for (JsonNode event : root.path("events")) {
            JsonNode type = event.path("status").path("type");
            games.add(new GameRef(
                    event.path("id").asText(),
                    event.path("name").asText(),
                    type.path("state").asText("unknown"),
                    type.path("detail").asText("")));
        }
        return games;
    }

    public List<GameRef> scoreboard() throws IOException, InterruptedException {
        JsonNode root = get(SCOREBOARD);
        List<GameRef> games = new ArrayList<>();
        for (JsonNode event : root.path("events")) {
            JsonNode type = event.path("status").path("type");
            games.add(new GameRef(
                    event.path("id").asText(),
                    event.path("name").asText(),
                    type.path("state").asText("unknown"),
                    type.path("detail").asText("")));
        }
        return games;
    }

    public List<GameRef> liveGames() throws IOException, InterruptedException {
        return scoreboard().stream().filter(GameRef::isLive).toList();
    }

    public JsonNode summary(String eventId) throws IOException, InterruptedException {
        return get(SUMMARY + eventId);
    }

    private JsonNode get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("ESPN returned " + response.statusCode() + " for " + url);
        }
        return mapper.readTree(response.body());
    }
}
