package dev.mikeyku.wheelhouse.sleeper;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Reads Sleeper's public API. Documented, free, no key.
 *
 * <p>The player list is several megabytes and changes at most once a day, so it is cached
 * on disk. Sleeper explicitly asks callers to fetch it about once a day rather than on
 * every request, and honouring that also makes restarts instant.
 */
@Component
public class SleeperClient {

    private static final Logger log = LoggerFactory.getLogger(SleeperClient.class);

    private static final String PLAYERS = "https://api.sleeper.app/v1/players/nfl";
    private static final String PROJECTIONS =
            "https://api.sleeper.com/projections/nfl/%d/%d?season_type=regular&order_by=ppr";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path cacheDir;
    private final Duration cacheTtl;

    public SleeperClient(
            @Value("${wheelhouse.sleeper.cache-dir}") String cacheDir,
            @Value("${wheelhouse.sleeper.cache-ttl-hours:20}") long cacheTtlHours) {
        this.cacheDir = Path.of(cacheDir);
        this.cacheTtl = Duration.ofHours(cacheTtlHours);
    }

    /** The full NFL player list. Served from disk unless the cached copy has aged out. */
    public JsonNode players() throws IOException, InterruptedException {
        return cached("players-nfl.json", PLAYERS);
    }

    /** Weekly per-stat projections, sourced by Sleeper from Rotowire. */
    public JsonNode projections(int season, int week) throws IOException, InterruptedException {
        return cached("projections-%d-%d.json".formatted(season, week),
                PROJECTIONS.formatted(season, week));
    }

    private JsonNode cached(String filename, String url) throws IOException, InterruptedException {
        Files.createDirectories(cacheDir);
        Path file = cacheDir.resolve(filename);

        if (Files.exists(file)) {
            Duration age = Duration.between(Files.getLastModifiedTime(file).toInstant(), Instant.now());
            if (age.compareTo(cacheTtl) < 0) {
                log.debug("{} from cache, age {}h", filename, age.toHours());
                return mapper.readTree(Files.readString(file));
            }
        }

        log.info("fetching {}", url);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // A stale cache beats no data at all when the upstream is having a bad day.
            if (Files.exists(file)) {
                log.warn("sleeper returned {}, falling back to stale cache", response.statusCode());
                return mapper.readTree(Files.readString(file));
            }
            throw new IOException("Sleeper returned " + response.statusCode() + " for " + url);
        }

        Files.writeString(file, response.body());
        return mapper.readTree(response.body());
    }
}
