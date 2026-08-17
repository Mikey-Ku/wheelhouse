package dev.mikeyku.wheelhouse.entry;

import dev.mikeyku.wheelhouse.contest.Contest;
import dev.mikeyku.wheelhouse.contest.ContestService;
import dev.mikeyku.wheelhouse.model.Player;
import dev.mikeyku.wheelhouse.model.Roster;
import dev.mikeyku.wheelhouse.model.Slot;
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
 * The build flow, twelve picks long: spin a team, spin a player, then decide which of the
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

    private final EntryRepository entries;
    private final SpinRepository spins;
    private final ContestService contests;
    private final WheelPool pool;

    private final int teamRespins;
    private final int playerRespins;

    /**
     * Defaults on, and must stay on in production: an unlocked week means someone can build a
     * roster after seeing the results. Turn it off only to exercise the flow out of season,
     * when every game on the current scoreboard has already finished.
     */
    private final boolean enforceLock;

    public EntryService(EntryRepository entries, SpinRepository spins, ContestService contests,
                        WheelPool pool,
                        @Value("${wheelhouse.contest.enforce-lock:true}") boolean enforceLock,
                        @Value("${wheelhouse.respins.team:3}") int teamRespins,
                        @Value("${wheelhouse.respins.player:3}") int playerRespins) {
        this.entries = entries;
        this.spins = spins;
        this.contests = contests;
        this.pool = pool;
        this.enforceLock = enforceLock;
        this.teamRespins = teamRespins;
        this.playerRespins = playerRespins;
    }

    /** A player has exactly one entry per week. Returning here resumes where they left off. */
    @Transactional
    public EntryRecord openEntry(String owner, Contest contest) {
        return entries.findByContestIdAndOwnerIgnoreCase(contest.id(), owner)
                .orElseGet(() -> entries.save(new EntryRecord(
                        UUID.randomUUID().toString(), contest.id(), owner.trim(), Instant.now(),
                        teamRespins, playerRespins)));
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

        // With six picks per position the wheel will land on a team whose only eligible
        // quarterback you already own: fifteen of thirty-two teams have exactly one. Exhausted
        // teams are filtered out before the spin rather than erroring after it.
        Set<String> rostered = rosteredPlayerIds(entry, pick.pickIndex());
        List<String> options = pool.teams(entry.contestId(), pick.slot()).stream()
                .filter(team -> pool.candidates(entry.contestId(), pick.slot(), team).stream()
                        .anyMatch(c -> !rostered.contains(c.id())))
                .toList();
        if (options.isEmpty()) {
            throw new IllegalStateException("no team has an eligible " + pick.slot() + " left");
        }
        // A respin that could hand back the same team is not a respin.
        if (respin && options.size() > 1) {
            String current = pick.team();
            options = options.stream().filter(t -> !t.equals(current)).toList();
        }

        pick.team(random(options, seed(entryId, pickIndex, TEAM, respin)));
        // The old player belonged to the old team, so it goes with it.
        pick.playerId(null);
        pick.option(null);
        if (respin) {
            entry.teamRespins(entry.teamRespins() - 1);
        }

        spins.save(new SpinRecord(entryId, pickIndex, TEAM, pick.team(), respin, Instant.now()));
        return entries.save(entry);
    }

    @Transactional
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

        Set<String> taken = rosteredPlayerIds(entry, pickIndex);
        List<Player> options = pool.candidates(entry.contestId(), pick.slot(), pick.team()).stream()
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

    public List<EntryRecord> forOwner(String owner) {
        return entries.findByOwnerIgnoreCaseOrderByCreatedAtDesc(owner);
    }

    public List<EntryRecord> forContest(String contestId) {
        return entries.findByContestId(contestId);
    }

    public EntryRecord byId(String entryId) {
        return entries.findById(entryId).orElse(null);
    }

    private EntryRecord require(String entryId) {
        EntryRecord entry = entries.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("no such entry"));
        // Archived weeks never lock; they are already over and are not a ranked competition.
        if (enforceLock && contests.byId(entry.contestId()).locked(Instant.now())) {
            throw new IllegalStateException("this week is locked, first kickoff has passed");
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
