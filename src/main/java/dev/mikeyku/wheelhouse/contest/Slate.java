package dev.mikeyku.wheelhouse.contest;

import dev.mikeyku.wheelhouse.model.Format;
import tools.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One lockable group of games inside a week.
 *
 * <p>A week that locks at its first kickoff is closed from Thursday night to the following
 * Tuesday, which is exactly when people want to play. So a week is split by day: every Sunday
 * game is one classic slate, locking at the first Sunday kickoff, and every other day is its own
 * showdown. Thursday and Monday are usually one game each; Thanksgiving and late-season
 * Saturdays hold several and still play as a showdown.
 *
 * <p>A slate's wheel only lands on its own teams. That is not a nicety: by Sunday the Thursday
 * teams have already played, and a wheel that could still land on them would hand out results.
 *
 * <p>Days are read in US Eastern, which is how the schedule is published. An 8:15pm Monday kickoff
 * is Tuesday in UTC.
 */
public record Slate(String key, String label, Format format, Instant lockAt, List<Game> games) {

    public static final ZoneId EASTERN = ZoneId.of("America/New_York");

    /** Team codes here are Sleeper's, the ones the wheel uses. */
    public record Game(String eventId, String away, String home, Instant kickoff) {}

    public boolean locked(Instant now) {
        return lockAt != null && !now.isBefore(lockAt);
    }

    public Set<String> teams() {
        Set<String> teams = new LinkedHashSet<>();
        for (Game g : games) {
            teams.add(g.away());
            teams.add(g.home());
        }
        return teams;
    }

    /** Slates for one scoreboard, in kickoff order. */
    public static List<Slate> from(JsonNode board) {
        Map<ZonedDateTime, List<Game>> byDay = new TreeMap<>();
        for (JsonNode event : board.path("events")) {
            Instant kickoff = parse(event.path("date").asText(null));
            if (kickoff == null) {
                continue;
            }
            String away = null;
            String home = null;
            for (JsonNode c : event.path("competitions").path(0).path("competitors")) {
                String team = TeamCodes.fromEspn(c.path("team").path("abbreviation").asText(null));
                if ("home".equals(c.path("homeAway").asText())) {
                    home = team;
                } else {
                    away = team;
                }
            }
            if (away == null || home == null) {
                continue;
            }
            ZonedDateTime day = kickoff.atZone(EASTERN).toLocalDate().atStartOfDay(EASTERN);
            byDay.computeIfAbsent(day, d -> new ArrayList<>())
                    .add(new Game(event.path("id").asText(), away, home, kickoff));
        }

        List<Slate> slates = new ArrayList<>();
        for (Map.Entry<ZonedDateTime, List<Game>> day : byDay.entrySet()) {
            List<Game> games = day.getValue();
            games.sort(Comparator.comparing(Game::kickoff));
            DayOfWeek dow = day.getKey().getDayOfWeek();
            String name = dow.getDisplayName(TextStyle.FULL, Locale.US);
            String key = dow.getDisplayName(TextStyle.SHORT, Locale.US).toLowerCase(Locale.US);
            Instant lock = games.get(0).kickoff();

            if (dow == DayOfWeek.SUNDAY) {
                slates.add(new Slate(key, name, Format.CLASSIC, lock, List.copyOf(games)));
            } else {
                boolean night = games.size() == 1
                        && games.get(0).kickoff().atZone(EASTERN).getHour() >= 17;
                slates.add(new Slate(key, night ? name + " Night" : name, Format.SHOWDOWN, lock,
                        List.copyOf(games)));
            }
        }
        return List.copyOf(slates);
    }

    /** ESPN omits seconds ({@code 2026-09-24T00:15Z}), which {@code Instant.parse} rejects. */
    private static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
