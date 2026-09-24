package dev.mikeyku.wheelhouse.web;

import dev.mikeyku.wheelhouse.account.AccountService;
import dev.mikeyku.wheelhouse.account.NotYours;
import dev.mikeyku.wheelhouse.account.UserRecord;
import dev.mikeyku.wheelhouse.contest.ArchiveService;
import dev.mikeyku.wheelhouse.contest.Contest;
import dev.mikeyku.wheelhouse.contest.ContestService;
import dev.mikeyku.wheelhouse.contest.Slate;
import dev.mikeyku.wheelhouse.contest.TeamCodes;
import dev.mikeyku.wheelhouse.entry.EntryRecord;
import dev.mikeyku.wheelhouse.entry.EntryService;
import dev.mikeyku.wheelhouse.form.FormService;
import dev.mikeyku.wheelhouse.ingest.IngestService;
import dev.mikeyku.wheelhouse.model.Format;
import dev.mikeyku.wheelhouse.model.GameSnapshot;
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
import jakarta.servlet.http.HttpServletRequest;
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

/** The build-and-score flow: pick a slate, fill its roster, watch it score. */
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
    private final AccountService accounts;
    private final Standings standings;

    public PlayController(EntryService entries, ContestService contests, ArchiveService archive,
                          PlayerCatalog catalog, AthleteResolver resolver, ScoringService scoring,
                          ProjectionService projections, WheelPool pool, FormService form,
                          IngestService ingest, PositionalField field, CaptureRate capture,
                          AccountService accounts, Standings standings) {
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
        this.accounts = accounts;
        this.standings = standings;
    }

    @GetMapping("/contest")
    public Map<String, Object> contest() {
        return describe(contests.current());
    }

    /**
     * This week's slates, soonest first, each with its games and whether it is still open.
     * The landing page leads with the first open one.
     */
    @GetMapping("/slates")
    public Map<String, Object> slates() {
        Contest week = contests.current();
        Instant now = Instant.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Slate slate : contests.slates()) {
            Map<String, Object> m = describe(slate, now);
            List<Map<String, Object>> games = new ArrayList<>();
            for (Slate.Game g : slate.games()) {
                Map<String, Object> gm = new LinkedHashMap<>();
                gm.put("away", g.away());
                gm.put("home", g.home());
                gm.put("awayLogo", logo(g.away()));
                gm.put("homeLogo", logo(g.home()));
                gm.put("kickoff", g.kickoff().toString());
                games.add(gm);
            }
            m.put("games", games);
            m.put("entries", week == null ? 0 : entries.forContest(week.id()).stream()
                    .filter(e -> slate.key().equals(e.slate())).count());
            out.add(m);
        }
        Slate next = contests.nextOpenSlate();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("week", week == null ? null : describe(week));
        body.put("next", next == null ? null : next.key());
        body.put("slates", out);
        return body;
    }

    private Map<String, Object> describe(Slate slate, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", slate.key());
        m.put("label", slate.label());
        m.put("format", slate.format().name());
        m.put("picks", slate.format().totalPicks());
        m.put("lockAt", slate.lockAt().toString());
        m.put("locked", slate.locked(now));
        return m;
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

    /** A new roster for a slate of this week, or for an archived week. Needs a profile. */
    @PostMapping("/open")
    public Map<String, Object> open(@RequestParam(required = false) Integer season,
                                    @RequestParam(required = false) Integer week,
                                    @RequestParam(required = false) String slate,
                                    HttpServletRequest request) {
        UserRecord user = accounts.require(request);
        if (season != null && week != null) {
            return view(entries.openEntry(user, archive.load(season, week), null));
        }
        // No slate named means the next one still open, which is what a bare "play" wants.
        Slate chosen = slate == null ? contests.nextOpenSlate() : contests.slate(slate);
        if (chosen == null) {
            throw new IllegalStateException(slate == null
                    ? "Every game this week has kicked off. Try a past week."
                    : "There's no " + slate + " slate this week.");
        }
        return view(entries.openEntry(user, contests.current(), chosen));
    }

    @GetMapping("/{entryId}")
    public Map<String, Object> get(@PathVariable String entryId, HttpServletRequest request) {
        return view(own(entryId, request));
    }

    /**
     * The entry, if it is the caller's, with its week in memory.
     *
     * <p>A roster belongs to a profile, and nobody else can read or change it by id: the id used
     * to be the whole credential, and it appears in the address bar. A roster from before
     * profiles has no owner yet and goes to whoever holds its id and is signed in.
     */
    private EntryRecord own(String entryId, HttpServletRequest request) {
        EntryRecord entry = entries.byId(entryId);
        if (entry == null) {
            throw new IllegalArgumentException("That roster doesn't exist.");
        }
        UserRecord user = accounts.current(request);
        if (entry.userId() == null) {
            if (user != null) {
                accounts.claim(user, List.of(entryId));
                entry = entries.byId(entryId);
            }
        } else if (user == null || !user.id().equals(entry.userId())) {
            throw new NotYours();
        }
        warm(entryId);
        return entry;
    }

    /**
     * Makes sure the week an entry belongs to is in memory before anything reads it.
     *
     * <p>Entries are on disk; the stats they are scored against and the player pool they name
     * are not. A resumed archive week would otherwise come back scoring zero with "unknown
     * player" on every row. Every endpoint that acts on an entry passes through here, not only
     * the read: with more weeks being played than the cap holds, a week was released between
     * two picks of a draft still in progress, and the next spin found an empty pool and
     * reported "no team has an eligible QB left". Passing through here also marks the week as
     * recently used, so a week somebody is drafting is never the stalest one. Loading is
     * idempotent and returns immediately once the week is already in memory.
     */
    private EntryRecord warm(String entryId) {
        EntryRecord entry = entries.byId(entryId);
        if (entry != null) {
            rehydrate(entry.contestId());
        }
        return entry;
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
                                    @RequestParam(defaultValue = "false") boolean respinPlayer,
            HttpServletRequest request) {
        own(entryId, request);
        if (!respinPlayer) {
            entries.spinTeam(entryId, index, respinTeam);
        }
        return view(entries.spinPlayer(entryId, index, respinPlayer));
    }

    @PostMapping("/{entryId}/pick/{index}/team")
    public Map<String, Object> spinTeam(@PathVariable String entryId, @PathVariable int index,
                                        @RequestParam(defaultValue = "false") boolean respin,
            HttpServletRequest request) {
        own(entryId, request);
        return view(entries.spinTeam(entryId, index, respin));
    }

    @PostMapping("/{entryId}/pick/{index}/player")
    public Map<String, Object> spinPlayer(@PathVariable String entryId, @PathVariable int index,
                                          @RequestParam(defaultValue = "false") boolean respin,
            HttpServletRequest request) {
        own(entryId, request);
        return view(entries.spinPlayer(entryId, index, respin));
    }

    @PostMapping("/{entryId}/pick/{index}/choose")
    public Map<String, Object> choose(@PathVariable String entryId, @PathVariable int index,
                                      @RequestParam String option,
            HttpServletRequest request) {
        own(entryId, request);
        return view(entries.choose(entryId, index, option));
    }

    /** A read-only link for a finished slip. Returns a token, never the entry id. */
    @PostMapping("/{entryId}/share")
    public Map<String, Object> share(@PathVariable String entryId, HttpServletRequest request) {
        own(entryId, request);
        return Map.of("shareId", entries.share(entryId));
    }

    /**
     * A shared slip, as anyone with the link sees it. The same view the owner gets, minus
     * everything that would let a reader act on the entry: its id and its respin budget.
     */
    @GetMapping("/shared/{shareId}")
    public Map<String, Object> shared(@PathVariable String shareId) {
        EntryRecord entry = entries.byShareId(shareId);
        if (entry == null || !entry.complete()) {
            throw new IllegalArgumentException("That link doesn't work anymore.");
        }
        rehydrate(entry.contestId());
        Map<String, Object> out = new LinkedHashMap<>(view(entry));
        out.remove("entryId");
        out.remove("teamRespins");
        out.remove("playerRespins");
        out.put("shared", true);
        return out;
    }

    /** A board as rows of names and totals. Never carries an entry id. */
    @GetMapping("/leaderboard")
    public List<Map<String, Object>> leaderboard(@RequestParam(required = false) String contestId,
                                                 @RequestParam(required = false) String slate) {
        String id = contestId == null ? contests.current().id() : contestId;
        return standings.board(id, slate, null).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", r.rank());
            m.put("owner", r.name());
            m.put("total", r.total());
            m.put("projectedTotal", r.projectedTotal());
            m.put("capturePercent", r.capturePercent());
            return m;
        }).toList();
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
        // False for the first minute after a cold start, while the player list is still being
        // fetched. The page shows "warming up" instead of an empty wheel or the wrong excuse.
        m.put("ready", catalog.size() > 0);
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

        Format format = entry.format();
        List<Map<String, Object>> positions = new ArrayList<>();
        for (int p = 0; p < format.positions().size(); p++) {
            List<EntryRecord.PickRecord> inPosition = entry.picksInPosition(p);
            List<Map<String, Object>> picks = inPosition.stream()
                    .map(pick -> describePick(entry, pick, byPick.get(pick.pickIndex()), revealed))
                    .toList();

            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("index", p);
            pos.put("slot", format.positions().get(p).name());
            // A showdown fills fewer picks than the position has parts; the page shows how many.
            pos.put("picks", format.picksIn(p));
            pos.put("parts", format.positions().get(p).options().size());
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
        out.put("guest", entry.guest());
        out.put("contest", describe(contests.byId(entry.contestId())));
        out.put("slate", entry.slate());
        out.put("slateLabel", standings.slateLabel(entry));
        out.put("format", format.name());
        // The entry's own lock, not the week's: Sunday is still open after Thursday kicks off.
        out.put("locked", entries.locked(entry));
        out.put("complete", revealed);
        out.put("revealed", revealed);
        out.put("projectedTotal", scored.projectedTotal());
        if (revealed) {
            out.put("total", scored.total());
            out.put("standing", standings.standing(entry));
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
        out.put("totalPicks", format.totalPicks());
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
        String opponent = opponentOf(entry, pick.team());
        m.put("opponent", opponent);
        m.put("game", standings.gameState(entry, pick.team()));
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
            m.put("teamReel", entries.teams(entry, pick.slot()).stream()
                    .limit(REEL_SIZE).toList());
            m.put("playerReel", playerReel(entry, pick));
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
        // Week one has no games this season, but the form falls back to the end of last season.
        if (contest == null || contest.seasonType() != 2) {
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
    private List<String> playerReel(EntryRecord entry, EntryRecord.PickRecord pick) {
        List<String> names = new ArrayList<>();
        if (pick.team() != null) {
            entries.candidates(entry, pick.slot(), pick.team()).stream()
                    .map(Player::name).filter(Objects::nonNull).forEach(names::add);
        }
        pool.candidates(entry.contestId(), pick.slot()).stream()
                .map(Player::name)
                .filter(Objects::nonNull)
                .filter(n -> !names.contains(n))
                .limit(Math.max(0, REEL_SIZE - names.size()))
                .forEach(names::add);
        return names.stream().limit(REEL_SIZE).toList();
    }

    /**
     * Who a team plays this week. Read off the box score once there is one, and off the slate
     * before that: a matchup is exactly what a pick should turn on, and before slates existed
     * a live week showed no opponent at all until the game had kicked off.
     */
    private String opponentOf(EntryRecord entry, String team) {
        String fromBox = ingest.opponentOf(entry.contestId(), team);
        if (fromBox != null) {
            return fromBox;
        }
        Slate slate = entries.slateOf(entry);
        if (slate == null || team == null) {
            return null;
        }
        for (Slate.Game g : slate.games()) {
            if (g.away().equals(team)) {
                return g.home();
            }
            if (g.home().equals(team)) {
                return g.away();
            }
        }
        return null;
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
}
