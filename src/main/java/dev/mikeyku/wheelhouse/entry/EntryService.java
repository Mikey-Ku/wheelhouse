package dev.mikeyku.wheelhouse.entry;

import dev.mikeyku.wheelhouse.contest.Contest;
import dev.mikeyku.wheelhouse.contest.ContestService;
import dev.mikeyku.wheelhouse.contest.Slate;
import dev.mikeyku.wheelhouse.model.Format;
import dev.mikeyku.wheelhouse.model.Player;
import dev.mikeyku.wheelhouse.model.Roster;
import dev.mikeyku.wheelhouse.model.Slot;
import dev.mikeyku.wheelhouse.projection.ProjectionService;
import dev.mikeyku.wheelhouse.scoring.ScoringService;
import dev.mikeyku.wheelhouse.wheel.WheelPool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The build flow, fourteen picks for a Sunday and seven for a single game: spin a team, spin a player, then decide which of the
 * position's remaining parts you spend them on.
 *
 * <p>The wheel decides who you get; you decide what they are for. The third pick in any
 * position takes whatever part is left, so spending Mahomes on the arm means the touchdowns
 * have to come from whoever the wheel hands you next.
 *
 * <p>Every spin is server-authoritative and written on first request, so refreshing returns
 * the result you already got rather than a new roll. Outcomes are seeded from the entry, the
 * pick and the attempt, which makes them reproducible: the same spin always resolves the same
 * way, whatever happens to the process in between.
 */
@Service
public class EntryService {

    private static final String TEAM = "TEAM";
    private static final String PLAYER = "PLAYER";
    /** Distinct seed material, so a team rolled inside a player respin cannot land where a
     *  separate team respin on the same pick would have. */
    private static final String TEAM_WITH_PLAYER = "TEAM_WITH_PLAYER";

    private final EntryRepository entries;
    private final SpinRepository spins;
    private final ContestService contests;
    private final WheelPool pool;
    private final ScoringService scoring;
    private final ProjectionService projections;

    /**
     * A showdown draws on everyone forecast for at least this many points at their best part,
     * rather than on the league-wide relevance cutoff. Low enough to keep a starting backup,
     * high enough to keep a fourth receiver who is projected for one catch off the wheel.
     */
    private final double showdownFloor;

    private final int teamRespins;
    private final int playerRespins;

    /**
     * Defaults on, and must stay on in production: an unlocked week means someone can build a
     * roster after seeing the results. Turn it off only to exercise the flow out of season,
     * when every game on the current scoreboard has already finished.
     */
    private final boolean enforceLock;

    public EntryService(EntryRepository entries, SpinRepository spins, ContestService contests,
                        WheelPool pool, ScoringService scoring, ProjectionService projections,
                        @Value("${wheelhouse.contest.enforce-lock:true}") boolean enforceLock,
                        @Value("${wheelhouse.wheel.showdown-floor:3.0}") double showdownFloor,
                        @Value("${wheelhouse.respins.team:3}") int teamRespins,
                        @Value("${wheelhouse.respins.player:3}") int playerRespins) {
        this.entries = entries;
        this.spins = spins;
        this.contests = contests;
        this.pool = pool;
        this.scoring = scoring;
        this.projections = projections;
        this.enforceLock = enforceLock;
        this.showdownFloor = showdownFloor;
        this.teamRespins = teamRespins;
        this.playerRespins = playerRespins;
    }

    @Transactional
    /**
     * Always a new roster, never a resumed one.
     *
     * <p>This used to hand back whatever you had already built for the same week, which made a
     * week a thing you got one attempt at. Replaying is the point: the wheel deals differently
     * every time, so the same week is a different problem on a second run, and going back to a
     * week you scored badly on is the reason to keep playing at all.
     *
     * <p>Resuming an unfinished draft still works and never came through here. It goes through
     * the entry id the browser is holding, which is the only thing that ever identified a
     * specific roster.
     */
    public EntryRecord openEntry(String owner, Contest contest, Slate slate) {
        if (enforceLock && slate != null && slate.locked(Instant.now())) {
            throw new IllegalStateException(slate.label() + " has kicked off");
        }
        Format format = slate == null ? Format.CLASSIC : slate.format();
        return entries.save(new EntryRecord(
                UUID.randomUUID().toString(), contest.id(), slate == null ? null : slate.key(),
                format, owner.trim(), Instant.now(), teamRespins, playerRespins));
    }

    @Transactional
    public EntryRecord spinTeam(String entryId, int pickIndex, boolean respin) {
        EntryRecord entry = require(entryId);
        EntryRecord.PickRecord pick = entry.pick(pickIndex);

        if (pick.team() != null && !respin) {
            return entry;
        }
        if (respin) {
            if (pick.team() == null) {
                throw new IllegalStateException("nothing to respin yet");
            }
            if (entry.teamRespins() <= 0) {
                throw new IllegalStateException("no team respins left");
            }
        }

        rollTeam(entry, pick, respin, seed(entryId, pickIndex, TEAM, respin));
        if (respin) {
            entry.teamRespins(entry.teamRespins() - 1);
        }

        spins.save(new SpinRecord(entryId, pickIndex, TEAM, pick.team(), respin, Instant.now()));
        return entries.save(entry);
    }

    @Transactional
    /**
     * Lands the wheel on a team and drops whatever belonged to the previous one.
     *
     * <p>Budget and audit accounting stay with the caller, because a quarterback respin rolls
     * the team as part of a single player respin rather than charging for both.
     */
    private void rollTeam(EntryRecord entry, EntryRecord.PickRecord pick,
                          boolean avoidCurrent, long seed) {
        // With several picks per position the wheel will land on a team whose only eligible
        // quarterback you already own: fifteen of thirty-two teams have exactly one. Exhausted
        // teams are filtered out before the spin rather than erroring after it.
        Set<String> rostered = rosteredPlayerIds(entry, pick.pickIndex());
        List<String> options = teams(entry, pick.slot()).stream()
                .filter(team -> candidates(entry, pick.slot(), team).stream()
                        .anyMatch(c -> !rostered.contains(c.id())))
                .toList();
        if (options.isEmpty()) {
            throw new IllegalStateException("no team has an eligible " + pick.slot() + " left");
        }
        // A respin that could hand back the same team is not a respin.
        if (avoidCurrent && options.size() > 1) {
            String current = pick.team();
            options = options.stream().filter(t -> !t.equals(current)).toList();
        }
        pick.team(random(options, seed));
        // The old player belonged to the old team, so it goes with it.
        pick.playerId(null);
        pick.option(null);
    }

    public EntryRecord spinPlayer(String entryId, int pickIndex, boolean respin) {
        EntryRecord entry = require(entryId);
        EntryRecord.PickRecord pick = entry.pick(pickIndex);

        if (pick.team() == null) {
            throw new IllegalStateException("spin a team first");
        }
        if (pick.playerId() != null && !respin) {
            return entry;
        }
        if (respin) {
            if (pick.playerId() == null) {
                throw new IllegalStateException("nothing to respin yet");
            }
            if (entry.playerRespins() <= 0) {
                throw new IllegalStateException("no player respins left");
            }
        }

        // A quarterback respin that keeps the team is not a respin at all: most clubs have one
        // eligible starter, so the filter below finds a single option, declines to exclude the
        // current player because excluding him would leave nothing, and hands back the same man
        // with a respin deducted. Rolling the team first is what makes the spin mean something.
        if (respin && pick.slot().playerRespinTakesTeam()) {
            rollTeam(entry, pick, true, seed(entryId, pickIndex, TEAM_WITH_PLAYER, true));
            spins.save(new SpinRecord(entryId, pickIndex, TEAM, pick.team(), true, Instant.now()));
        }

        Set<String> taken = rosteredPlayerIds(entry, pickIndex);
        List<Player> options = candidates(entry, pick.slot(), pick.team()).stream()
                .filter(p -> !taken.contains(p.id()))
                .toList();

        if (respin && options.size() > 1) {
            String current = pick.playerId();
            options = options.stream().filter(p -> !p.id().equals(current)).toList();
        }
        if (options.isEmpty()) {
            throw new IllegalStateException(
                    "everyone eligible on " + pick.team() + " is already on your roster");
        }

        Player player = random(options, seed(entryId, pickIndex, PLAYER, respin));
        pick.playerId(player.id());
        pick.option(null);
        if (respin) {
            entry.playerRespins(entry.playerRespins() - 1);
        }

        spins.save(new SpinRecord(entryId, pickIndex, PLAYER, player.id(), respin, Instant.now()));
        return entries.save(entry);
    }

    /**
     * The only real decision in the game: which of this position's remaining parts the player
     * you just drew is going to cover.
     */
    @Transactional
    public EntryRecord choose(String entryId, int pickIndex, String option) {
        EntryRecord entry = require(entryId);
        EntryRecord.PickRecord pick = entry.pick(pickIndex);

        if (pick.playerId() == null) {
            throw new IllegalStateException("spin a player first");
        }
        pick.slot().option(option)
                .orElseThrow(() -> new IllegalArgumentException(
                        option + " is not an option for " + pick.slot()));

        // Each part is covered once per position, which is what forces the allocation to matter.
        boolean alreadyUsed = entry.picksInPosition(pick.position()).stream()
                .anyMatch(p -> p.pickIndex() != pickIndex && option.equalsIgnoreCase(p.option()));
        if (alreadyUsed) {
            throw new IllegalStateException(option + " is already covered in this position");
        }

        pick.option(option);
        if (entry.complete() && entry.submittedAt() == null) {
            entry.submittedAt(Instant.now());
        }
        return entries.save(entry);
    }

    /**
     * Teams the wheel may land on for this entry: the slate's own teams, or the whole pool for
     * an archived week or an entry written before slates existed.
     */
    public List<String> teams(EntryRecord entry, Slot slot) {
        Slate slate = slateOf(entry);
        if (slate == null) {
            return pool.teams(entry.contestId(), slot);
        }
        Set<String> playing = slate.teams();
        return playing.stream()
                .filter(team -> !candidates(entry, slot, team).isEmpty())
                .sorted()
                .toList();
    }

    /** Who a team spin can resolve to for this entry. */
    public List<Player> candidates(EntryRecord entry, Slot slot, String team) {
        if (entry.format() != Format.SHOWDOWN || !projections.available(entry.contestId())) {
            return pool.candidates(entry.contestId(), slot, team);
        }
        return pool.everyone(slot, team).stream()
                .filter(p -> bestProjection(entry.contestId(), slot, p) >= showdownFloor)
                .toList();
    }

    private double bestProjection(String contestId, Slot slot, Player player) {
        double best = 0;
        for (Slot.StatOption option : slot.options()) {
            best = Math.max(best, scoring.scoreOne(contestId, slot, player, option).projectedPoints());
        }
        return best;
    }

    /**
     * The slate an entry belongs to, while its week is still the current one. Once the week
     * has moved on the slate is gone, and so is any reason to spin for it.
     */
    public Slate slateOf(EntryRecord entry) {
        if (entry.slate() == null) {
            return null;
        }
        Contest now = contests.current();
        if (now == null || !now.id().equals(entry.contestId())) {
            return null;
        }
        return contests.slate(entry.slate());
    }

    /**
     * Whether an entry can still be changed. Archived weeks never lock. A live entry locks with
     * its slate, and an entry for a week that is no longer current is locked outright: before
     * slates, a week that had rolled over rebuilt itself with no lock time at all, so an
     * unfinished roster from last week could still be drafted with every result known.
     */
    public boolean locked(EntryRecord entry) {
        Contest contest = contests.byId(entry.contestId());
        if (contest == null || contest.archive()) {
            return false;
        }
        Contest now = contests.current();
        if (now == null || !now.id().equals(entry.contestId())) {
            return true;
        }
        Slate slate = slateOf(entry);
        return slate != null ? slate.locked(Instant.now()) : now.locked(Instant.now());
    }

    /** Nobody appears twice on the same roster, in any position. */
    private Set<String> rosteredPlayerIds(EntryRecord entry, int excludingPick) {
        return entry.picks().stream()
                .filter(p -> p.pickIndex() != excludingPick)
                .map(EntryRecord.PickRecord::playerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** Parts nobody in this position has taken yet. */
    public List<Slot.StatOption> remainingOptions(EntryRecord entry, int pickIndex) {
        EntryRecord.PickRecord pick = entry.pick(pickIndex);
        Set<String> used = entry.picksInPosition(pick.position()).stream()
                .filter(p -> p.pickIndex() != pickIndex)
                .map(EntryRecord.PickRecord::option)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return pick.slot().options().stream()
                .filter(o -> used.stream().noneMatch(u -> u.equalsIgnoreCase(o.key())))
                .toList();
    }

    public Roster asRoster(EntryRecord entry) {
        List<Roster.Pick> picks = entry.picks().stream()
                .filter(EntryRecord.PickRecord::filled)
                .map(p -> new Roster.Pick(p.slot(), p.playerId(), p.option()))
                .toList();
        return new Roster(entry.id(), entry.contestId(), entry.owner(), picks);
    }

    public List<SpinRecord> history(String entryId) {
        return spins.findByEntryIdOrderByAtAsc(entryId);
    }


    public List<EntryRecord> forContest(String contestId) {
        return entries.findByContestId(contestId);
    }

    /** The link token for a slip, minted the first time it is shared. */
    @Transactional
    public String share(String entryId) {
        EntryRecord entry = entries.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("no such entry"));
        if (!entry.complete()) {
            throw new IllegalStateException("finish the roster first");
        }
        if (entry.shareId() == null) {
            entry.shareId(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            entries.save(entry);
        }
        return entry.shareId();
    }

    public EntryRecord byShareId(String shareId) {
        return shareId == null ? null : entries.findByShareId(shareId).orElse(null);
    }

    /**
     * Renames the roster. The name is what the leaderboard shows, so it is allowed after the
     * lock as well; nothing about the picks changes.
     */
    @Transactional
    public EntryRecord rename(String entryId, String owner) {
        EntryRecord entry = entries.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("no such entry"));
        String name = owner == null ? "" : owner.strip().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > 24) {
            throw new IllegalArgumentException("names are 1 to 24 characters");
        }
        entry.owner(name);
        return entries.save(entry);
    }

    public EntryRecord byId(String entryId) {
        return entries.findById(entryId).orElse(null);
    }

    private EntryRecord require(String entryId) {
        EntryRecord entry = entries.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("no such entry"));
        if (enforceLock && locked(entry)) {
            throw new IllegalStateException("locked, kickoff has passed");
        }
        return entry;
    }

    private <T> T random(List<T> options, long seed) {
        if (options.isEmpty()) {
            throw new IllegalStateException("wheel has nothing to land on");
        }
        return options.get(new Random(seed).nextInt(options.size()));
    }

    /**
     * A spin's outcome is a function of which spin it is, not of when it happens. Same entry,
     * same pick, same attempt, same result, forever.
     */
    private long seed(String entryId, int pickIndex, String kind, boolean respin) {
        String material = entryId + ":" + pickIndex + ":" + kind + ":" + (respin ? 1 : 0);
        long hash = 1125899906842597L;
        for (byte b : material.getBytes(StandardCharsets.UTF_8)) {
            hash = 31 * hash + b;
        }
        return hash;
    }
}
