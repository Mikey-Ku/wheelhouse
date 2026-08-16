package dev.mikeyku.wheelhouse.espn;

import dev.mikeyku.wheelhouse.model.GameSnapshot;
import dev.mikeyku.wheelhouse.model.StatKey;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns an ESPN {@code /summary} payload into a {@link GameSnapshot}.
 *
 * <p>The payload shape is {@code boxscore.players[] -> statistics[] -> athletes[]}, where
 * each athlete carries a positional {@code stats} array of strings that lines up with the
 * category's {@code keys} array. Values are always mapped by key, never by index, because
 * categories have different shapes and ESPN reorders them without warning.
 */
@Component
public class BoxscoreParser {

    public GameSnapshot parse(String contestId, String eventId, JsonNode summary, Instant fetchedAt) {
        Map<StatKey, Double> stats = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, String> teams = new HashMap<>();

        JsonNode players = summary.path("boxscore").path("players");
        for (JsonNode teamBlock : players) {
            String team = teamBlock.path("team").path("abbreviation").asText("UNK");
            for (JsonNode category : teamBlock.path("statistics")) {
                String categoryName = category.path("name").asText();
                JsonNode keys = category.path("keys");
                for (JsonNode athleteEntry : category.path("athletes")) {
                    JsonNode athlete = athleteEntry.path("athlete");
                    String athleteId = athlete.path("id").asText();
                    if (athleteId.isBlank()) {
                        continue;
                    }
                    names.putIfAbsent(athleteId, athlete.path("displayName").asText());
                    teams.putIfAbsent(athleteId, team);

                    JsonNode values = athleteEntry.path("stats");
                    int n = Math.min(keys.size(), values.size());
                    for (int i = 0; i < n; i++) {
                        put(stats, athleteId, categoryName, keys.get(i).asText(), values.get(i).asText());
                    }
                }
            }
        }

        JsonNode status = summary.path("header").path("competitions").path(0).path("status");
        String state = status.path("type").path("state").asText("unknown");
        String detail = status.path("type").path("detail").asText("");
        String name = summary.path("header").path("competitions").path(0)
                .path("competitors").path(0).path("team").path("displayName").asText("");

        return new GameSnapshot(contestId, eventId, name, state, detail, fetchedAt,
                Map.copyOf(stats), Map.copyOf(names), Map.copyOf(teams));
    }

    /**
     * Writes one raw value, splitting compound stats into their components.
     *
     * <p>ESPN packs some stats two-to-a-cell: the key {@code completions/passingAttempts}
     * carries the value {@code "23/35"}, and {@code sacks-sackYardsLost} carries {@code "2-14"}.
     * The separator is detected on the key rather than the value, so a negative number like
     * {@code "-3"} rushing yards is never mistaken for a compound.
     */
    private void put(Map<StatKey, Double> out, String athleteId, String category, String key, String raw) {
        if (raw == null || raw.isBlank() || raw.equals("--")) {
            return;
        }
        String separator = key.contains("/") ? "/" : key.contains("-") ? "-" : null;
        if (separator != null) {
            String[] keyParts = key.split(java.util.regex.Pattern.quote(separator));
            String[] valueParts = raw.split(java.util.regex.Pattern.quote(separator));
            if (keyParts.length == valueParts.length) {
                for (int i = 0; i < keyParts.length; i++) {
                    putSimple(out, athleteId, category, keyParts[i], valueParts[i]);
                }
                return;
            }
        }
        putSimple(out, athleteId, category, key, raw);
    }

    private void putSimple(Map<StatKey, Double> out, String athleteId, String category, String key, String raw) {
        Double value = toNumber(raw);
        if (value != null) {
            out.put(new StatKey(athleteId, category, key), value);
        }
    }

    private Double toNumber(String raw) {
        String cleaned = raw.trim().replace(",", "").replace("%", "");
        if (cleaned.isEmpty() || cleaned.equals("--")) {
            return null;
        }
        try {
            return Double.valueOf(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
