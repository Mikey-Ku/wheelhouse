package dev.mikeyku.wheelhouse.web;

import dev.mikeyku.wheelhouse.contest.Contest;
import dev.mikeyku.wheelhouse.contest.ContestService;
import dev.mikeyku.wheelhouse.contest.Slate;
import dev.mikeyku.wheelhouse.contest.TeamCodes;
import dev.mikeyku.wheelhouse.entry.EntryRecord;
import dev.mikeyku.wheelhouse.entry.EntryRepository;
import dev.mikeyku.wheelhouse.entry.EntryService;
import dev.mikeyku.wheelhouse.ingest.IngestService;
import dev.mikeyku.wheelhouse.model.GameSnapshot;
import dev.mikeyku.wheelhouse.scoring.CaptureRate;
import dev.mikeyku.wheelhouse.scoring.ScoringService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Who is ahead, where a roster stands, and what state it is in. Shared by the play view, the
 * profile and the leaderboard pages so all three agree about the same roster.
 *
 * <p>A board is one slate of one week, or one archived week. Each profile appears once, with
 * its best finished roster: a slate or a past week can be played again, and a board that listed
 * every attempt would reward whoever played most rather than whoever played best.
 */
@Component
public class Standings {

    private final EntryService entries;
    private final EntryRepository repository;
    private final ContestService contests;
    private final ScoringService scoring;
    private final CaptureRate capture;
    private final IngestService ingest;

    public Standings(EntryService entries, EntryRepository repository, ContestService contests,
                     ScoringService scoring, CaptureRate capture, IngestService ingest) {
        this.entries = entries;
        this.repository = repository;
        this.contests = contests;
        this.scoring = scoring;
        this.capture = capture;
        this.ingest = ingest;
    }

    /** One line of a board. {@code entryId} is only ever filled in for the viewer's own row. */
    public record Row(int rank, String name, double total, double projectedTotal,
                      Integer capturePercent, boolean mine, String entryId) {}

    public List<Row> board(String contestId, String slate, String viewerId) {
        Map<String, EntryRecord> best = new LinkedHashMap<>();
        Map<String, Double> totals = new HashMap<>();
        for (EntryRecord e : entries.forContest(contestId)) {
            if (!e.complete() || e.guest() || !Objects.equals(e.slate(), slate)) {
                continue;
            }
            double total = scoring.score(entries.asRoster(e)).total();
            String who = e.userId() != null ? e.userId() : "entry:" + e.id();
            EntryRecord current = best.get(who);
            if (current == null || total > totals.get(current.id())) {
                best.put(who, e);
            }
            totals.put(e.id(), total);
        }

        List<EntryRecord> ranked = new ArrayList<>(best.values());
        Map<String, Double> projected = new HashMap<>();
        for (EntryRecord e : ranked) {
            projected.put(e.id(), scoring.score(entries.asRoster(e)).projectedTotal());
        }
        ranked.sort(Comparator.comparingDouble((EntryRecord e) -> -totals.get(e.id()))
                .thenComparingDouble(e -> -projected.get(e.id())));

        List<Row> rows = new ArrayList<>();
        int rank = 0;
        double previous = Double.NaN;
        for (int i = 0; i < ranked.size(); i++) {
            EntryRecord e = ranked.get(i);
            double total = totals.get(e.id());
            // Ties share a rank: two people on 41.2 are both second, and the next is fourth.
            if (total != previous) {
                rank = i + 1;
                previous = total;
            }
            boolean mine = viewerId != null && viewerId.equals(e.userId());
            CaptureRate.Result c = capture.of(e);
            rows.add(new Row(rank, e.owner(), round(total), round(projected.get(e.id())),
                    c == null ? null : c.capturePercent(), mine, mine ? e.id() : null));
        }
        return rows;
    }

    /** Where one finished roster would sit on its board, counting each profile's best. */
    public Map<String, Object> standing(EntryRecord entry) {
        // A guest is not on the board, so a rank would describe a place they do not hold.
        if (!entry.complete() || entry.guest()) {
            return null;
        }
        double mine = scoring.score(entries.asRoster(entry)).total();
        Map<String, Double> bestOthers = new HashMap<>();
        for (EntryRecord e : entries.forContest(entry.contestId())) {
            if (!e.complete() || e.guest() || !Objects.equals(e.slate(), entry.slate())) {
                continue;
            }
            String who = e.userId() != null ? e.userId() : "entry:" + e.id();
            String me = entry.userId() != null ? entry.userId() : "entry:" + entry.id();
            if (who.equals(me)) {
                continue;
            }
            double t = scoring.score(entries.asRoster(e)).total();
            bestOthers.merge(who, t, Math::max);
        }
        long ahead = bestOthers.values().stream().filter(t -> t > mine).count();
        return Map.of("rank", (int) ahead + 1, "of", bestOthers.size() + 1);
    }

    /**
     * Not started, in progress, or over. A pick whose game cannot be found is treated as over:
     * after a restart a finished week's box scores are gone, and "pending" forever would be a
     * lie told about a game that was played days ago.
     */
    public String status(EntryRecord entry) {
        if (!entry.complete()) {
            return "building";
        }
        Contest c = contests.byId(entry.contestId());
        if (c == null || c.archive()) {
            return "final";
        }
        boolean anyStarted = false;
        boolean allOver = true;
        for (EntryRecord.PickRecord p : entry.picks()) {
            Map<String, Object> g = gameState(entry, p.team());
            String state = g == null ? "post" : String.valueOf(g.get("state"));
            if (!"pre".equals(state)) {
                anyStarted = true;
            }
            if (!"post".equals(state)) {
                allOver = false;
            }
        }
        return allOver ? "final" : anyStarted ? "live" : "pending";
    }

    /** Where a team's game is: not started with its kickoff, in progress with its clock, or final. */
    public Map<String, Object> gameState(EntryRecord entry, String team) {
        if (team == null) {
            return null;
        }
        for (GameSnapshot snapshot : ingest.snapshots(entry.contestId())) {
            boolean plays = snapshot.athleteTeams().values().stream()
                    .anyMatch(t -> team.equals(TeamCodes.fromEspn(t)));
            if (plays) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("state", snapshot.state());
                g.put("detail", snapshot.detail());
                return g;
            }
        }
        Slate slate = entries.slateOf(entry);
        if (slate != null) {
            for (Slate.Game g : slate.games()) {
                if (g.away().equals(team) || g.home().equals(team)) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("state", "pre");
                    out.put("kickoff", g.kickoff().toString());
                    return out;
                }
            }
        }
        return null;
    }

    public String slateLabel(EntryRecord entry) {
        return slateLabel(entry.slate(), entries.slateOf(entry));
    }

    static String slateLabel(String key, Slate live) {
        if (key == null) {
            return null;
        }
        if (live != null) {
            return live.label();
        }
        // The week has moved on and its slates with it; the key still names the day.
        return switch (key) {
            case "sun" -> "Sunday";
            case "mon" -> "Monday Night";
            case "thu" -> "Thursday Night";
            case "sat" -> "Saturday";
            case "fri" -> "Friday";
            default -> key;
        };
    }

    /** A name for a board: "Week 3 · Sunday", "2023 · Week 9". */
    public String boardLabel(String contestId, String slate) {
        Contest c = contests.byId(contestId);
        if (c == null) {
            return contestId;
        }
        if (c.archive()) {
            return c.season() + " · Week " + c.week();
        }
        Contest now = contests.current();
        String week = (now != null && now.season() != c.season() ? c.season() + " " : "") + c.label();
        String day = slateLabel(slate, now != null && now.id().equals(contestId)
                ? contests.slate(slate) : null);
        return day == null ? week : week + " · " + day;
    }

    /** One of your rosters, as the profile lists it. */
    public Map<String, Object> summary(EntryRecord entry) {
        ScoringService.ScoredRoster scored = scoring.score(entries.asRoster(entry));
        Contest c = contests.byId(entry.contestId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entryId", entry.id());
        m.put("contestId", entry.contestId());
        m.put("slate", entry.slate());
        m.put("label", boardLabel(entry.contestId(), entry.slate()));
        m.put("archive", c != null && c.archive());
        m.put("current", c != null && !c.archive() && contests.current() != null
                && contests.current().id().equals(c.id()));
        m.put("createdAt", entry.createdAt() == null ? null : entry.createdAt().toString());
        m.put("complete", entry.complete());
        m.put("locked", entries.locked(entry));
        m.put("picked", entry.picks().stream().filter(EntryRecord.PickRecord::filled).count());
        m.put("totalPicks", entry.picks().size());
        String status = status(entry);
        m.put("status", status);
        m.put("projectedTotal", round(scored.projectedTotal()));
        if (entry.complete()) {
            m.put("total", round(scored.total()));
            m.put("standing", standing(entry));
            CaptureRate.Result cap = "final".equals(status) ? capture.of(entry) : null;
            m.put("capturePercent", cap == null ? null : cap.capturePercent());
        }
        return m;
    }

    /**
     * Every board worth listing: this week's slates always, and any other week somebody has
     * finished a roster for.
     */
    public List<Map<String, Object>> boards() {
        Contest now = contests.current();
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        if (now != null) {
            for (Slate s : contests.slates()) {
                byKey.put(now.id() + "|" + s.key(), board(now, s.key(), s.label(), s.lockAt()));
            }
        }
        Map<String, java.util.Set<String>> players = new HashMap<>();
        for (EntryRecord e : repository.findAll()) {
            if (!e.complete() || e.guest()) {
                continue;
            }
            String key = e.contestId() + "|" + e.slate();
            if (!byKey.containsKey(key)) {
                Contest c = contests.byId(e.contestId());
                if (c == null) {
                    continue;
                }
                Map<String, Object> b = board(c, e.slate(), null, null);
                b.put("label", boardLabel(e.contestId(), e.slate()));
                byKey.put(key, b);
            }
            players.computeIfAbsent(key, k -> new java.util.HashSet<>())
                    .add(e.userId() != null ? e.userId() : "entry:" + e.id());
        }
        List<Map<String, Object>> out = new ArrayList<>(byKey.values());
        for (Map<String, Object> b : out) {
            b.put("players", players.getOrDefault(b.get("contestId") + "|" + b.get("slate"),
                    java.util.Set.of()).size());
        }
        // This week first, then earlier weeks of this season, then the archive, newest first.
        out.sort(Comparator.comparingInt((Map<String, Object> b) -> (int) b.get("group"))
                .thenComparing(b -> -(int) b.get("season") * 100 - (int) b.get("week")));
        return out;
    }

    private Map<String, Object> board(Contest c, String slate, String label, Instant lockAt) {
        Contest now = contests.current();
        boolean current = now != null && now.id().equals(c.id());
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("contestId", c.id());
        b.put("slate", slate);
        b.put("label", label != null ? c.label() + " · " + label : boardLabel(c.id(), slate));
        b.put("archive", c.archive());
        b.put("current", current);
        b.put("lockAt", lockAt == null ? null : lockAt.toString());
        b.put("season", c.season());
        b.put("week", c.week());
        b.put("group", current ? 0 : c.archive() ? 2 : 1);
        return b;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
