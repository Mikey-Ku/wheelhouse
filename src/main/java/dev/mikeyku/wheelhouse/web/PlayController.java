package dev.mikeyku.wheelhouse.web;

import dev.mikeyku.wheelhouse.contest.ArchiveService;
import dev.mikeyku.wheelhouse.contest.Contest;
import dev.mikeyku.wheelhouse.contest.ContestService;
import dev.mikeyku.wheelhouse.entry.EntryRecord;
import dev.mikeyku.wheelhouse.entry.EntryService;
import dev.mikeyku.wheelhouse.form.FormService;
import dev.mikeyku.wheelhouse.ingest.IngestService;
import dev.mikeyku.wheelhouse.model.Player;
import dev.mikeyku.wheelhouse.model.Roster;
import dev.mikeyku.wheelhouse.model.Slot;
import dev.mikeyku.wheelhouse.projection.PositionalField;
import dev.mikeyku.wheelhouse.projection.ProjectionService;
import dev.mikeyku.wheelhouse.scoring.CaptureRate;
import dev.mikeyku.wheelhouse.scoring.ScoringService;
import dev.mikeyku.wheelhouse.sleeper.AthleteResolver;
import dev.mikeyku.wheelhouse.sleeper.PlayerCatalog;
import dev.mikeyku.wheelhouse.wheel.WheelPool;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The build-and-score flow: seventeen picks across four composite positions. */
@RestController
@RequestMapping("/api/play")
public class PlayController {

    /** How many candidates to send for the spin reel. Enough to look full, not the whole pool. */
    private static final int REEL_SIZE = 24;

    private final EntryService entries;
    private final ContestService contests;
    private final ArchiveService archive;
    private final PlayerCatalog catalog;
    private final AthleteResolver resolver;
    private final ScoringService scoring;
    private final ProjectionService projections;
    private final WheelPool pool;
    private final FormService form;
    private final IngestService ingest;
    private final PositionalField field;
    private final CaptureRate capture;

    public PlayController(EntryService entries, ContestService contests, ArchiveService archive,
                          PlayerCatalog catalog, AthleteResolver resolver, ScoringService scoring,
                          ProjectionService projections, WheelPool pool, FormService form,
                          IngestService ingest, PositionalField field, CaptureRate capture) {
        this.entries = entries;
        this.contests = contests;
        this.archive = archive;
        this.catalog = catalog;
        this.resolver = resolver;
        this.scoring = scoring;
        this.projections = projections;
        this.pool = pool;
        this.form = form;
        this.ingest = ingest;
        this.field = field;
        this.capture = capture;
    }

    @GetMapping("/contest")
    public Map<String, Object> contest() {
        return describe(contests.current());
    }

    /**
     * Which seasons the archive can reach, and how long a roster is.
     *
     * <p>The pick count ships from here so the page never hardcodes it. Dropping a single part
     * from one position changes the length of the whole draft, and the last time that happened
     * the word "eighteen" was left behind in six places.
     */
    @GetMapping("/archive")
    public Map<String, Object> archiveRange() {
        return Map.of(
                "earliest", archive.earliestSeason(),
                "latest", archive.latestSeason(),
                "weeks", 18,
                "totalPicks", Roster.TOTAL_PICKS);
    }

    @PostMapping("/open")
    public Map<String, Object> open(@RequestParam String owner,
                                    @RequestParam(required = false) Integer season,
                                    @RequestParam(required = false) Integer week) {
        Contest contest = (season != null && week != null)
                ? archive.load(season, week)
                : contests.current();
        return view(entries.openEntry(owner, contest));
    }

    @GetMapping("/{entryId}")
    public Map<String, Object> get(@PathVariable String entryId) {
        EntryRecord entry = entries.byId(entryId);
        // Entries are on disk now; the stats they are scored against and the player pool they
        // name are not. A resumed archive week would otherwise come back scoring zero with
        // "unknown player" on every row. Loading is idempotent and returns immediately once
        // the week is already in memory.
        if (entry != null) {
            rehydrate(entry.contestId());
        }
        return view(entry);
    }

    /** Pulls an archived week back in if this process has not seen it yet. */
    private void rehydrate(String contestId) {
        Contest contest = contests.byId(contestId);
        if (contest == null || !contest.archive()) {
            return;
        }
        try {
            archive.load(contest.season(), contest.week());
        } catch (RuntimeException e) {
            // Out of range now, or ESPN is unreachable. The view still renders what it holds.
        }
    }

    /**
     * Resolves both reels in one call, because the client spins them side by side and a player
     * cannot be drawn until the team is known. A team respin necessarily re-rolls the player
     * too; a player respin keeps the team.
     */
    @PostMapping("/{entryId}/pick/{index}/spin")
    public Map<String, Object> spin(@PathVariable String entryId, @PathVariable int index,
                                    @RequestParam(defaultValue = "false") boolean respinTeam,
                                    @RequestParam(defaultValue = "false") boolean respinPlayer) {
        if (!respinPlayer) {
            entries.spinTeam(entryId, index, respinTeam);
        }
        return view(entries.spinPlayer(entryId, index, respinPlayer));
    }

    @PostMapping("/{entryId}/pick/{index}/team")
    public Map<String, Object> spinTeam(@PathVariable String entryId, @PathVariable int index,
                                        @RequestParam(defaultValue = "false") boolean respin) {
        return view(entries.spinTeam(entryId, index, respin));
    }

    @PostMapping("/{entryId}/pick/{index}/player")
    public Map<String, Object> spinPlayer(@PathVariable String entryId, @PathVariable int index,
                                          @RequestParam(defaultValue = "false") boolean respin) {
        return view(entries.spinPlayer(entryId, index, respin));
    }

    @PostMapping("/{entryId}/pick/{index}/choose")
    public Map<String, Object> choose(@PathVariable String entryId, @PathVariable int index,
                                      @RequestParam String option) {
        return view(entries.choose(entryId, index, option));
    }

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

    private Map<String, Object> describe(Contest c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("label", c.label());
        m.put("season", c.season());
        m.put("week", c.week());
        m.put("archive", c.archive());
        m.put("lockAt", c.lockAt() == null ? "" : c.lockAt().toString());
        m.put("locked", c.locked(Instant.now()));
        // Preseason and postseason carry no forecasts at all. The UI has to say so rather than
        // present a wall of zeroes as though every player were expected to do nothing.
        m.put("hasProjections", projections.available(c.id()));
        return m;
    }

    private Map<String, Object> summary(EntryRecord entry) {
        ScoringService.ScoredRoster scored = scoring.score(entries.asRoster(entry));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entryId", entry.id());
        m.put("owner", entry.owner());
        m.put("contestId", entry.contestId());
        m.put("complete", entry.complete());
        m.put("projectedTotal", scored.projectedTotal());
        // A leaderboard is a side channel. Half-built rosters report nothing they have not yet
        // earned the right to see.
        m.put("total", entry.complete() ? scored.total() : 0.0);
        return m;
    }

    /**
     * The view the client gets.
     *
     * <p>While the roster is being built this carries projections and nothing else. Actual
     * results are withheld on the server rather than merely hidden by the page, because a
     * blind draft that ships the answers in the same payload is theatre.
     */
    private Map<String, Object> view(EntryRecord entry) {
        if (entry == null) {
            return Map.of("error", "no such entry");
        }
        boolean revealed = entry.complete();
        ScoringService.ScoredRoster scored = scoring.score(entries.asRoster(entry));
        EntryRecord.PickRecord active = entry.activePick();

        // Points come from the scored roster, never by searching the options list. Deriving
        // them from the options would silently convert the entire scoreboard to projected
        // numbers the moment those options started carrying forecasts.
        Map<Integer, ScoringService.ScoredPick> byPick = new LinkedHashMap<>();
        List<EntryRecord.PickRecord> filled = entry.picks().stream()
                .filter(EntryRecord.PickRecord::filled).toList();
        for (int i = 0; i < filled.size() && i < scored.picks().size(); i++) {
            byPick.put(filled.get(i).pickIndex(), scored.picks().get(i));
        }

        List<Map<String, Object>> positions = new ArrayList<>();
        for (int p = 0; p < Roster.POSITIONS.size(); p++) {
            List<EntryRecord.PickRecord> inPosition = entry.picksInPosition(p);
            List<Map<String, Object>> picks = inPosition.stream()
                    .map(pick -> describePick(entry, pick, byPick.get(pick.pickIndex()), revealed))
                    .toList();

            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("index", p);
            pos.put("slot", Roster.POSITIONS.get(p).name());
            pos.put("projectedTotal", round(sum(inPosition, byPick, true)));
            if (revealed) {
                pos.put("total", round(sum(inPosition, byPick, false)));
            }
            pos.put("complete", inPosition.stream().allMatch(EntryRecord.PickRecord::filled));
            pos.put("picks", picks);
            positions.add(pos);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entryId", entry.id());
        out.put("owner", entry.owner());
        out.put("contest", describe(contests.byId(entry.contestId())));
        out.put("complete", revealed);
        out.put("revealed", revealed);
        out.put("projectedTotal", scored.projectedTotal());
        if (revealed) {
            out.put("total", scored.total());
            // How much of the board you were dealt you actually took. Only meaningful once
            // every pick is in, which is also the only point at which it can be computed.
            CaptureRate.Result c = capture.of(entry);
            if (c != null) {
                out.put("capture", c);
            }
        }
        out.put("teamRespins", entry.teamRespins());
        out.put("playerRespins", entry.playerRespins());
        out.put("activePick", active == null ? null : active.pickIndex());
        out.put("totalPicks", Roster.TOTAL_PICKS);
        out.put("positions", positions);
        return out;
    }

    private double sum(List<EntryRecord.PickRecord> picks,
                       Map<Integer, ScoringService.ScoredPick> byPick, boolean projected) {
        return picks.stream()
                .map(pick -> byPick.get(pick.pickIndex()))
                .filter(Objects::nonNull)
                .mapToDouble(s -> projected ? s.projectedPoints() : s.points())
                .sum();
    }

    private Map<String, Object> describePick(EntryRecord entry, EntryRecord.PickRecord pick,
                                             ScoringService.ScoredPick scored, boolean revealed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pickIndex", pick.pickIndex());
        m.put("position", pick.position());
        m.put("slot", pick.slot().name());
        m.put("team", pick.team());
        m.put("teamLogo", logo(pick.team()));
        String opponent = ingest.opponentOf(entry.contestId(), pick.team());
        m.put("opponent", opponent);
        m.put("opponentLogo", logo(opponent));
        m.put("chosen", pick.option());

        // The form guide is only assembled for the pick being played. Every other pick is
        // already decided, and building it for every pick would mean an outbound request per
        // player on a view the client polls every fifteen seconds.
        EntryRecord.PickRecord active = entry.activePick();
        boolean isActive = active != null && active.pickIndex() == pick.pickIndex();

        Player player = pick.playerId() == null ? null : catalog.byId(pick.playerId());
        if (player != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", player.id());
            p.put("name", player.name() == null ? "" : player.name());
            p.put("position", player.position() == null ? "" : player.position());
            p.put("team", player.team() == null ? "" : player.team());
            // An injury status is a reading of today, not of the week on screen. Attaching it
            // to an archived contest would report a player as out of a game he has already
            // played, using a knock he picked up two seasons later.
            Contest now = contests.byId(entry.contestId());
            if (now != null && !now.archive()
                    && player.injuryStatus() != null && !player.injuryStatus().isBlank()) {
                p.put("injury", player.injuryStatus());
            }
            String image = headshot(player);
            if (image != null) {
                p.put("image", image);
            }
            m.put("player", p);
        }

        // Only the parts still open in this position, priced against the forecast.
        List<Map<String, Object>> options = new ArrayList<>();
        List<Slot.StatOption> offer = pick.option() == null
                ? entries.remainingOptions(entry, pick.pickIndex())
                : pick.slot().options();

        for (Slot.StatOption o : offer) {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("key", o.key());
            om.put("part", o.label());
            om.put("stat", o.description());
            om.put("unit", o.unit());
            // The card shows its own arithmetic: how many of the thing, times what, equals the
            // points. A score nobody can check is worse than one that is imperfectly balanced.
            om.put("multiplier", scoring.multiplierFor(pick.slot(), o));
            om.put("volatile", o.volatile_());
            if (player != null) {
                ScoringService.ScoredPick probe =
                        scoring.scoreOne(entry.contestId(), pick.slot(), player, o);
                om.put("projectedRaw", probe.projectedRaw());
                om.put("projectedPoints", probe.projectedPoints());
                // Where this sits against everyone else who could have filled the same part.
                // The game pays points, not over-unders, so this is what makes a pick good.
                om.put("rank", field.percentile(entry.contestId(), pick.slot(), o, probe.projectedRaw()));
                Double mid = field.median(entry.contestId(), pick.slot(), o);
                om.put("fieldMedian", mid == null ? null : round(mid * probe.multiplier()));
                if (isActive) {
                    FormService.Form recent =
                            formFor(entry, player, o, probe.projectedRaw());
                    if (recent != null) {
                        om.put("form", recent);
                    }
                }
                if (revealed) {
                    om.put("raw", probe.raw());
                    om.put("points", probe.points());
                }
            }
            options.add(om);
        }
        m.put("options", options);

        if (scored != null) {
            m.put("projectedPoints", scored.projectedPoints());
            m.put("projectedRaw", scored.projectedRaw());
            if (revealed) {
                m.put("points", scored.points());
                m.put("raw", scored.raw());
            }
        }

        // The reel the client scrolls through before landing. Only the pick being played needs
        // it, and only the names: the server has already decided the result, the animation is
        // just showing its work.
        if (isActive) {
            m.put("teamReel", pool.teams(entry.contestId(), pick.slot()).stream()
                    .limit(REEL_SIZE).toList());
            m.put("playerReel", playerReel(entry.contestId(), pick));
        }
        return m;
    }

    /**
     * How this player has done at this part in the weeks leading up to the one being drafted.
     *
     * <p>Bounded to the regular season on purpose. Preseason and playoff weeks both number
     * themselves from one, so week two of the playoffs would silently match against week two of
     * the regular season and present a divisional round as early-September form.
     */
    private FormService.Form formFor(EntryRecord entry, Player player,
                                     Slot.StatOption option, Double line) {
        Contest contest = contests.byId(entry.contestId());
        if (contest == null || contest.seasonType() != 2 || contest.week() <= 1) {
            return null;
        }
        String espnId = resolver.espnIdFor(player);
        return espnId == null
                ? null
                : form.formFor(espnId, contest.season(), contest.week(), option, line);
    }

    /**
     * Names for the player reel to blur through.
     *
     * <p>Padded with league-wide candidates, because a team can have exactly one eligible
     * quarterback and a reel showing the same name forty times does not read as a spin. The
     * padding is decoration only: the server has already decided the result.
     */
    private List<String> playerReel(String contestId, EntryRecord.PickRecord pick) {
        List<String> names = new ArrayList<>();
        if (pick.team() != null) {
            pool.candidates(contestId, pick.slot(), pick.team()).stream()
                    .map(Player::name).filter(Objects::nonNull).forEach(names::add);
        }
        pool.candidates(contestId, pick.slot()).stream()
                .map(Player::name)
                .filter(Objects::nonNull)
                .filter(n -> !names.contains(n))
                .limit(Math.max(0, REEL_SIZE - names.size()))
                .forEach(names::add);
        return names.stream().limit(REEL_SIZE).toList();
    }

    /**
     * A face for the reveal.
     *
     * <p>ESPN first, through its combiner rather than the raw file: 30KB against 257KB, and a
     * cache lifetime measured in hours rather than the raw path's sub-minute Varnish countdown.
     * Sleeper's CDN covers the rest, which matters because ESPN ids are unknown for anyone who
     * has not yet appeared in a box score and Sleeper stopped publishing them altogether for
     * players who arrived from 2021 onward.
     */
    /**
     * A club crest for an abbreviation.
     *
     * <p>ESPN keys these by lowercase abbreviation and its vocabulary is the same one the box
     * scores use, so no mapping table is needed. Washington is the exception both feeds have
     * disagreed on for years.
     *
     * <p>The {@code 500-dark} set, not the default one. Roughly a third of the league has a
     * navy or black primary crest, and on this page those render as a slightly darker smudge
     * on an already dark panel: the Rams measure 67 of 255 in average brightness against a
     * background of about 30. The dark-mode set inverts exactly those and leaves the rest
     * alone, so the Rams come back at 255 and the Bears are served unchanged.
     */
    private String logo(String team) {
        if (team == null || team.isBlank() || team.equals("?")) {
            return null;
        }
        String abbr = team.equalsIgnoreCase("WAS") ? "wsh" : team.toLowerCase();
        return "https://a.espncdn.com/i/teamlogos/nfl/500-dark/" + abbr + ".png";
    }

    private String headshot(Player player) {
        String espnId = resolver.espnIdFor(player);
        if (espnId != null && !espnId.isBlank()) {
            return "https://a.espncdn.com/combiner/i?img=/i/headshots/nfl/players/full/"
                    + espnId + ".png&w=200&h=145";
        }
        // Archived players carry a synthetic id that exists in no external system, so a numeric
        // id is the signal that this is a real Sleeper player and worth asking Sleeper about.
        if (player.id() != null && !player.id().isEmpty()
                && player.id().chars().allMatch(Character::isDigit)) {
            return "https://sleepercdn.com/content/nfl/players/" + player.id() + ".jpg";
        }
        return null;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
