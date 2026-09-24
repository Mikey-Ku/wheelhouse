package dev.mikeyku.wheelhouse.form;

import dev.mikeyku.wheelhouse.espn.EspnClient;
import dev.mikeyku.wheelhouse.model.Slot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a player has actually been doing, for the weeks before the one being drafted.
 *
 * <p>The game asks you to bet on a stat you were handed at random, and until now the only
 * evidence on offer was a single projection. That makes the decision a coin flip dressed up as
 * a choice. A form guide turns it into a real read: this receiver is projected for sixty yards
 * and has cleared sixty once in six weeks, so the projection is generous and you should spend
 * your pick elsewhere.
 *
 * <p><b>The window is a hard boundary, not a display preference.</b> ESPN's game log returns
 * the entire season, which for an archived contest includes the week you are drafting and every
 * week after it. Serving any of that would hand over the answer mid-draft and quietly destroy
 * the whole point of the blind format, so the filter is strict and stated in one place.
 */
@Service
public class FormService {

    private static final Logger log = LoggerFactory.getLogger(FormService.class);

    /** Six games is what a form guide means, and what fits beside an option without shouting. */
    private static final int WINDOW = 6;

    private final EspnClient espn;

    /**
     * Keyed by player and season, least recently read first, capped. Failures cache as an empty
     * season deliberately: the roster view is polled every fifteen seconds while a week is live,
     * and an uncached miss would turn one unlucky request into a permanent retry loop against
     * ESPN. A restart clears it. The cap exists because this is keyed by player rather than by
     * week, so releasing a week cannot release its game logs; the bound does that instead.
     */
    private final Map<String, List<Game>> bySeason;

    public FormService(EspnClient espn,
                       @Value("${wheelhouse.form.max-players:400}") int maxPlayers) {
        this.espn = espn;
        this.bySeason = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Game>> stalest) {
                return size() > maxPlayers;
            }
        });
    }

    /** One completed game: who they played, and every stat ESPN recorded for them in it. */
    public record Game(int season, int week, String opponent, boolean home, String result,
                       Map<String, Double> stats) {}

    /** One prior week as the client draws it. */
    public record Outing(int season, int week, String opponent, boolean home, String result,
                         double value, boolean cleared) {}

    /**
     * A part's recent history, measured against the line the player is being offered now.
     *
     * @param cleared how many of these games reached the current projection, which is the whole
     *                question the format asks and the one number a projection alone cannot answer
     */
    public record Form(List<Outing> games, double average, double best, int cleared, int played,
                       Double line) {}

    /**
     * A part's form ahead of one week.
     *
     * @param beforeWeek the week being drafted; nothing from this week or later is ever included
     * @param line       the current projection, or null if there is none to measure against
     */
    public Form formFor(String espnId, int season, int beforeWeek,
                        Slot.StatOption option, Double line) {
        List<Game> played = before(espnId, season, beforeWeek);
        if (played.isEmpty()) {
            return null;
        }

        List<String> wanted = option.stats().stream().map(Slot.StatRef::stat).toList();
        // A tight end has no rushing columns at all. That is different from a tight end who
        // rushed for nothing, and only the second one belongs in a form guide.
        if (played.stream().noneMatch(g -> wanted.stream().anyMatch(g.stats()::containsKey))) {
            return null;
        }

        List<Outing> outings = new ArrayList<>();
        for (Game game : played) {
            double value = wanted.stream()
                    .mapToDouble(stat -> game.stats().getOrDefault(stat, 0.0))
                    .sum();
            outings.add(new Outing(game.season(), game.week(), game.opponent(), game.home(), game.result(),
                    round(value), line != null && value >= line));
        }

        double average = outings.stream().mapToDouble(Outing::value).average().orElse(0);
        double best = outings.stream().mapToDouble(Outing::value).max().orElse(0);
        int cleared = (int) outings.stream().filter(Outing::cleared).count();
        return new Form(outings, round(average), round(best), cleared, outings.size(), line);
    }

    /**
     * The last few games this player finished before the given week.
     *
     * <p>Regular season only, and strictly earlier than {@code beforeWeek}. Byes and missed
     * games mean the weeks are not contiguous, which is why this filters on the week number
     * rather than counting backwards from the end of the list.
     *
     * <p>Early in a season there are not six games yet, so the window is topped up from the
     * end of the previous one. Week three used to show a starter's two games, and week one
     * showed nothing at all. Last season is entirely before the week being drafted, so this
     * cannot leak anything.
     */
    public List<Game> before(String espnId, int season, int beforeWeek) {
        if (espnId == null || espnId.isBlank()) {
            return List.of();
        }
        List<Game> prior = new ArrayList<>(season(espnId, season).stream()
                .filter(g -> g.week() < beforeWeek)
                .sorted(Comparator.comparingInt(Game::week))
                .toList());
        if (prior.size() < WINDOW) {
            List<Game> last = season(espnId, season - 1).stream()
                    .sorted(Comparator.comparingInt(Game::week))
                    .toList();
            int need = Math.min(WINDOW - prior.size(), last.size());
            prior.addAll(0, last.subList(last.size() - need, last.size()));
        }
        return prior.size() <= WINDOW
                ? List.copyOf(prior)
                : List.copyOf(prior.subList(prior.size() - WINDOW, prior.size()));
    }

    private List<Game> season(String espnId, int season) {
        String key = espnId + "|" + season;
        List<Game> games = bySeason.get(key);
        if (games != null) {
            return games;
        }
        // Fetched outside the map's lock: computeIfAbsent on a synchronized map would hold every
        // other lookup behind one ESPN call. Two threads racing the same player just fetch twice.
        try {
            games = parse(espn.gamelog(espnId, season), season);
        } catch (Exception e) {
            log.debug("no game log for {} in {}: {}", espnId, season, e.toString());
            games = List.of();
        }
        List<Game> raced = bySeason.putIfAbsent(key, games);
        return raced != null ? raced : games;
    }

    /**
     * ESPN returns the stat names once at the root and every game as a bare positional array,
     * so the two are zipped back together here. Postseason games live in their own bucket and
     * number their weeks from one, which would collide with the regular season if both were
     * read, so only the regular season is taken.
     */
    private List<Game> parse(JsonNode root, int season) {
        List<String> names = new ArrayList<>();
        for (JsonNode name : root.path("names")) {
            names.add(name.asText());
        }
        JsonNode events = root.path("events");

        List<Game> games = new ArrayList<>();
        for (JsonNode seasonType : root.path("seasonTypes")) {
            if (!seasonType.path("displayName").asText("").contains("Regular Season")) {
                continue;
            }
            for (JsonNode category : seasonType.path("categories")) {
                for (JsonNode entry : category.path("events")) {
                    Game game = game(events, names, entry, season);
                    if (game != null) {
                        games.add(game);
                    }
                }
            }
        }
        return List.copyOf(games);
    }

    private Game game(JsonNode events, List<String> names, JsonNode entry, int season) {
        JsonNode meta = events.path(entry.path("eventId").asText());
        if (meta.isMissingNode() || !meta.has("week")) {
            return null;
        }

        Map<String, Double> stats = new LinkedHashMap<>();
        JsonNode values = entry.path("stats");
        for (int i = 0; i < names.size() && i < values.size(); i++) {
            Double value = number(values.get(i).asText(""));
            if (value != null) {
                stats.put(names.get(i), value);
            }
        }

        return new Game(
                season,
                meta.path("week").asInt(),
                meta.path("opponent").path("abbreviation").asText(""),
                "vs".equalsIgnoreCase(meta.path("atVs").asText("")),
                meta.path("gameResult").asText(""),
                stats);
    }

    /** ESPN writes an absent stat as a dash rather than omitting the column. */
    private Double number(String raw) {
        String cleaned = raw.replace(",", "").trim();
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals("--")) {
            return null;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
