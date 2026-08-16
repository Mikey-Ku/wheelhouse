package dev.mikeyku.wheelhouse.web;

import dev.mikeyku.wheelhouse.contest.Contest;
import dev.mikeyku.wheelhouse.contest.ArchiveService;
import dev.mikeyku.wheelhouse.contest.ContestService;
import dev.mikeyku.wheelhouse.entry.EntryRecord;
import dev.mikeyku.wheelhouse.entry.EntryService;
import dev.mikeyku.wheelhouse.model.Player;
import dev.mikeyku.wheelhouse.model.Roster;
import dev.mikeyku.wheelhouse.model.Slot;
import dev.mikeyku.wheelhouse.scoring.ScoringService;
import dev.mikeyku.wheelhouse.sleeper.PlayerCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The whole build-and-score flow. */
@RestController
@RequestMapping("/api/play")
public class PlayController {

    private final EntryService entries;
    private final ContestService contests;
    private final ArchiveService archive;
    private final PlayerCatalog catalog;
    private final ScoringService scoring;

    public PlayController(EntryService entries, ContestService contests, ArchiveService archive,
                          PlayerCatalog catalog, ScoringService scoring) {
        this.entries = entries;
        this.contests = contests;
        this.archive = archive;
        this.catalog = catalog;
        this.scoring = scoring;
    }

    @GetMapping("/contest")
    public Map<String, Object> contest() {
        return describe(contests.current());
    }

    private Map<String, Object> describe(Contest c) {
        return Map.of(
                "id", c.id(),
                "label", c.label(),
                "season", c.season(),
                "week", c.week(),
                "archive", c.archive(),
                "lockAt", c.lockAt() == null ? "" : c.lockAt().toString(),
                "locked", c.locked(Instant.now()));
    }

    /**
     * Opens this week's entry, or an archived week's if season and week are supplied. Loading
     * an archived week pulls its box scores on first request and then never again.
     */
    @PostMapping("/open")
    public Map<String, Object> open(@RequestParam String owner,
                                    @RequestParam(required = false) Integer season,
                                    @RequestParam(required = false) Integer week) {
        Contest contest = (season != null && week != null)
                ? archive.load(season, week)
                : contests.current();
        return view(entries.openEntry(owner, contest));
    }

    /** Which seasons the archive can reach. */
    @GetMapping("/archive")
    public Map<String, Object> archiveRange() {
        return Map.of(
                "earliest", ArchiveService.EARLIEST_SEASON,
                "latest", contests.current().season() - 1,
                "weeks", 18,
                "projectionsFrom", 2019);
    }

    @GetMapping("/{entryId}")
    public Map<String, Object> get(@PathVariable String entryId) {
        return view(entries.byId(entryId));
    }

    @PostMapping("/{entryId}/slot/{index}/team")
    public Map<String, Object> spinTeam(@PathVariable String entryId, @PathVariable int index,
                                        @RequestParam(defaultValue = "false") boolean respin) {
        return view(entries.spinTeam(entryId, index, respin));
    }

    @PostMapping("/{entryId}/slot/{index}/player")
    public Map<String, Object> spinPlayer(@PathVariable String entryId, @PathVariable int index,
                                          @RequestParam(defaultValue = "false") boolean respin) {
        return view(entries.spinPlayer(entryId, index, respin));
    }

    @PostMapping("/{entryId}/slot/{index}/choose")
    public Map<String, Object> choose(@PathVariable String entryId, @PathVariable int index,
                                      @RequestParam String option) {
        return view(entries.choose(entryId, index, option));
    }

    /** Every week this player has entered, newest first. */
    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam String owner) {
        return entries.forOwner(owner).stream().map(this::summary).toList();
    }

    @GetMapping("/leaderboard")
    public List<Map<String, Object>> leaderboard(@RequestParam(required = false) String contestId) {
        return entries.forContest(contestId == null ? contests.current().id() : contestId).stream()
                .map(this::summary)
                .sorted(Comparator.comparingDouble(m -> -(double) m.get("total")))
                .toList();
    }

    private Map<String, Object> summary(EntryRecord entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entryId", entry.id());
        m.put("owner", entry.owner());
        m.put("contestId", entry.contestId());
        m.put("complete", entry.complete());
        m.put("total", scoring.score(entries.asRoster(entry)).total());
        return m;
    }

    private Map<String, Object> view(EntryRecord entry) {
        if (entry == null) {
            return Map.of("error", "no such entry");
        }
        Roster roster = entries.asRoster(entry);
        ScoringService.ScoredRoster scored = scoring.score(roster);

        Map<String, Double> pointsByKey = new LinkedHashMap<>();
        scored.picks().forEach(p -> pointsByKey.put(p.slot() + "|" + p.playerId(), p.points()));

        List<Map<String, Object>> slots = entry.slots().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", s.slotIndex());
            m.put("slot", s.slot().name());
            m.put("team", s.team());
            m.put("teamRespinAvailable", s.team() != null && !s.teamRespun());
            m.put("playerRespinAvailable", s.playerId() != null && !s.playerRespun());
            m.put("chosen", s.option());

            Player player = s.playerId() == null ? null : catalog.byId(s.playerId());
            if (player != null) {
                m.put("player", Map.of(
                        "id", player.id(),
                        "name", player.name() == null ? "" : player.name(),
                        "position", player.position() == null ? "" : player.position(),
                        "team", player.team() == null ? "" : player.team()));
            }

            // Every option priced live, so after kickoff you can see the road not taken.
            m.put("options", s.slot().options().stream().map(o -> {
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("key", o.key());
                om.put("part", o.label());
                om.put("stat", o.category() + "." + o.stat());
                if (player != null) {
                    ScoringService.ScoredPick probe = scoring.score(new Roster(
                            "probe", entry.contestId(), "", List.of(
                            new Roster.Pick(s.slot(), player.id(), o.key())))).picks().get(0);
                    om.put("raw", probe.raw());
                    om.put("points", probe.points());
                }
                return om;
            }).toList());
            return m;
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entryId", entry.id());
        out.put("owner", entry.owner());
        out.put("contest", describe(contests.byId(entry.contestId())));
        out.put("complete", entry.complete());
        out.put("total", scored.total());
        out.put("slots", slots);
        return out;
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
