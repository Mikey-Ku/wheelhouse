package dev.mikeyku.wheelhouse.web;

import dev.mikeyku.wheelhouse.contest.ContestService;
import dev.mikeyku.wheelhouse.contest.Slate;
import dev.mikeyku.wheelhouse.contest.TeamCodes;
import dev.mikeyku.wheelhouse.espn.EspnClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Live page: every game this week with its score and clock, and each game's leaders.
 *
 * <p>Read from ESPN's public scoreboard and game summary, both cached briefly so a room full
 * of people watching the same page costs ESPN one request, not one each. A finished game's
 * leaders never change, so those are kept until the week turns over.
 */
@RestController
@RequestMapping("/api/scores")
public class ScoresController {

    private static final Duration BOARD_TTL = Duration.ofSeconds(15);
    private static final Duration LIVE_GAME_TTL = Duration.ofSeconds(20);

    private final EspnClient espn;
    private final ContestService contests;

    private volatile Map<String, Object> board;
    private volatile Instant boardAt = Instant.EPOCH;
    private final Map<String, Cached> games = new ConcurrentHashMap<>();

    private record Cached(Map<String, Object> body, Instant at, boolean done) {}

    public ScoresController(EspnClient espn, ContestService contests) {
        this.espn = espn;
        this.contests = contests;
    }

    @GetMapping
    public synchronized Map<String, Object> scores() throws Exception {
        if (board != null && Instant.now().isBefore(boardAt.plus(BOARD_TTL))) {
            return board;
        }
        JsonNode raw = espn.scoreboardRaw();
        Map<String, String> slateOf = new java.util.HashMap<>();
        for (Slate s : contests.slates()) {
            for (Slate.Game g : s.games()) {
                slateOf.put(g.eventId(), s.key());
            }
        }

        List<Map<String, Object>> events = new ArrayList<>();
        for (JsonNode e : raw.path("events")) {
            JsonNode comp = e.path("competitions").path(0);
            JsonNode type = e.path("status").path("type");
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("id", e.path("id").asText());
            g.put("kickoff", instant(e.path("date").asText(null)));
            g.put("state", type.path("state").asText("pre"));
            g.put("detail", type.path("shortDetail").asText(""));
            g.put("slate", slateOf.get(e.path("id").asText()));
            List<String> networks = new ArrayList<>();
            for (JsonNode b : comp.path("broadcasts")) {
                for (JsonNode n : b.path("names")) {
                    networks.add(n.asText());
                }
            }
            g.put("network", String.join(" / ", networks));
            for (JsonNode c : comp.path("competitors")) {
                Map<String, Object> t = new LinkedHashMap<>();
                String abbr = TeamCodes.fromEspn(c.path("team").path("abbreviation").asText());
                t.put("abbr", abbr);
                t.put("name", c.path("team").path("shortDisplayName").asText(abbr));
                t.put("logo", "https://a.espncdn.com/i/teamlogos/nfl/500-dark/"
                        + (abbr.equals("WAS") ? "wsh" : abbr.toLowerCase()) + ".png");
                t.put("score", c.path("score").asText(""));
                t.put("record", c.path("records").path(0).path("summary").asText(""));
                t.put("winner", c.path("winner").asBoolean(false));
                g.put("home".equals(c.path("homeAway").asText()) ? "home" : "away", t);
            }
            events.add(g);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("week", contests.current() == null ? null : contests.current().label());
        out.put("games", events);
        board = out;
        boardAt = Instant.now();
        return out;
    }

    /** Each team's passing, rushing and receiving leader, as ESPN writes the line. */
    @GetMapping("/{eventId}")
    public Map<String, Object> game(@PathVariable String eventId) throws Exception {
        if (!eventId.matches("\\d{1,12}")) {
            throw new IllegalArgumentException("That game doesn't exist.");
        }
        Cached hit = games.get(eventId);
        if (hit != null && (hit.done() || Instant.now().isBefore(hit.at().plus(LIVE_GAME_TTL)))) {
            return hit.body();
        }
        JsonNode summary = espn.summary(eventId);
        List<Map<String, Object>> teams = new ArrayList<>();
        for (JsonNode t : summary.path("leaders")) {
            Map<String, Object> team = new LinkedHashMap<>();
            team.put("abbr", TeamCodes.fromEspn(t.path("team").path("abbreviation").asText()));
            List<Map<String, Object>> lines = new ArrayList<>();
            for (JsonNode cat : t.path("leaders")) {
                JsonNode top = cat.path("leaders").path(0);
                String name = cat.path("name").asText();
                // Only the three the game is built from. Tackles and sacks are not on any roster.
                if (top.isMissingNode() || !List.of("passingYards", "rushingYards", "receivingYards").contains(name)) {
                    continue;
                }
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("stat", switch (name) {
                    case "passingYards" -> "Passing";
                    case "rushingYards" -> "Rushing";
                    case "receivingYards" -> "Receiving";
                    default -> cat.path("displayName").asText();
                });
                line.put("name", top.path("athlete").path("displayName").asText());
                line.put("line", top.path("displayValue").asText());
                line.put("headshot", top.path("athlete").path("headshot").path("href").asText(null));
                lines.add(line);
            }
            team.put("leaders", lines);
            teams.add(team);
        }
        String state = summary.path("header").path("competitions").path(0)
                .path("status").path("type").path("state").asText("pre");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", eventId);
        out.put("state", state);
        out.put("teams", teams);
        if (games.size() > 64) {
            games.clear();
        }
        games.put(eventId, new Cached(out, Instant.now(), "post".equals(state)));
        return out;
    }

    private static String instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
