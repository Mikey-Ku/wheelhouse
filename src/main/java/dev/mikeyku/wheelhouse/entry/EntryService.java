package dev.mikeyku.wheelhouse.entry;

import dev.mikeyku.wheelhouse.contest.Contest;
import dev.mikeyku.wheelhouse.contest.ContestService;
import dev.mikeyku.wheelhouse.model.Player;
import dev.mikeyku.wheelhouse.model.Roster;
import dev.mikeyku.wheelhouse.model.Slot;
import dev.mikeyku.wheelhouse.sleeper.PlayerCatalog;
import dev.mikeyku.wheelhouse.wheel.WheelPool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The build flow: spin a team, spin a player from that team, choose what you take from them.
 * One respin available on each spin.
 *
 * <p>Every spin is server-authoritative and written on first request, so refreshing returns
 * the result you already got rather than a new roll. Results are also derived from a seed
 * built out of the entry, the slot and the attempt number, which makes them reproducible:
 * the same spin always resolves the same way, whatever happens to the process in between.
 */
@Service
public class EntryService {

    private static final String TEAM = "TEAM";
    private static final String PLAYER = "PLAYER";

    private final EntryRepository entries;
    private final SpinRepository spins;
    private final ContestService contests;
    private final WheelPool pool;
    private final PlayerCatalog catalog;

    /**
     * Defaults on, and must stay on in production: an unlocked week means someone can build a
     * roster after seeing the results. Turn it off only to exercise the flow out of season,
     * when every game on the current scoreboard has already finished.
     */
    private final boolean enforceLock;

    public EntryService(EntryRepository entries, SpinRepository spins, ContestService contests,
                        WheelPool pool, PlayerCatalog catalog,
                        @Value("${wheelhouse.contest.enforce-lock:true}") boolean enforceLock) {
        this.entries = entries;
        this.spins = spins;
        this.contests = contests;
        this.pool = pool;
        this.catalog = catalog;
        this.enforceLock = enforceLock;
    }

    /** A player has exactly one entry per week. Returning here resumes where they left off. */
    @Transactional
    public EntryRecord openEntry(String owner, Contest contest) {
        return entries.findByContestIdAndOwnerIgnoreCase(contest.id(), owner)
                .orElseGet(() -> entries.save(new EntryRecord(
                        UUID.randomUUID().toString(), contest.id(), owner.trim(), Instant.now())));
    }

    @Transactional
    public EntryRecord spinTeam(String entryId, int slotIndex, boolean respin) {
        EntryRecord entry = require(entryId);
        EntryRecord.SlotRecord slot = entry.slot(slotIndex);

        if (slot.team() != null && !respin) {
            return entry;
        }
        if (respin) {
            if (slot.team() == null) {
                throw new IllegalStateException("nothing to respin yet");
            }
            if (slot.teamRespun()) {
                throw new IllegalStateException("team respin already used on this slot");
            }
        }

        List<String> options = pool.teams(entry.contestId(), slot.slot());
        // A respin that could hand back the same team is not a respin.
        if (respin && options.size() > 1) {
            String current = slot.team();
            options = options.stream().filter(t -> !t.equals(current)).toList();
        }

        String team = pick(options, seed(entryId, slotIndex, TEAM, respin));
        slot.team(team);
        // The old player belonged to the old team, so it goes with it.
        slot.playerId(null);
        slot.option(null);
        if (respin) {
            slot.teamRespun(true);
        }

        spins.save(new SpinRecord(entryId, slotIndex, TEAM, team, respin, Instant.now()));
        return entries.save(entry);
    }

    @Transactional
    public EntryRecord spinPlayer(String entryId, int slotIndex, boolean respin) {
        EntryRecord entry = require(entryId);
        EntryRecord.SlotRecord slot = entry.slot(slotIndex);

        if (slot.team() == null) {
            throw new IllegalStateException("spin a team first");
        }
        if (slot.playerId() != null && !respin) {
            return entry;
        }
        if (respin) {
            if (slot.playerId() == null) {
                throw new IllegalStateException("nothing to respin yet");
            }
            if (slot.playerRespun()) {
                throw new IllegalStateException("player respin already used on this slot");
            }
        }

        // Nobody appears twice on the same roster.
        Set<String> taken = entry.slots().stream()
                .filter(s -> s.slotIndex() != slotIndex)
                .map(EntryRecord.SlotRecord::playerId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        List<Player> options = pool.candidates(entry.contestId(), slot.slot(), slot.team()).stream()
                .filter(p -> !taken.contains(p.id()))
                .toList();

        if (respin && options.size() > 1) {
            String current = slot.playerId();
            options = options.stream().filter(p -> !p.id().equals(current)).toList();
        }
        if (options.isEmpty()) {
            throw new IllegalStateException("no eligible players left on " + slot.team());
        }

        Player player = pick(options, seed(entryId, slotIndex, PLAYER, respin));
        slot.playerId(player.id());
        slot.option(null);
        if (respin) {
            slot.playerRespun(true);
        }

        spins.save(new SpinRecord(entryId, slotIndex, PLAYER, player.id(), respin, Instant.now()));
        return entries.save(entry);
    }

    /** The only real decision in the game. */
    @Transactional
    public EntryRecord choose(String entryId, int slotIndex, String option) {
        EntryRecord entry = require(entryId);
        EntryRecord.SlotRecord slot = entry.slot(slotIndex);

        if (slot.playerId() == null) {
            throw new IllegalStateException("spin a player first");
        }
        slot.slot().option(option)
                .orElseThrow(() -> new IllegalArgumentException(
                        option + " is not an option for " + slot.slot()));

        slot.option(option);
        if (entry.complete() && entry.submittedAt() == null) {
            entry.submittedAt(Instant.now());
        }
        return entries.save(entry);
    }

    public Roster asRoster(EntryRecord entry) {
        List<Roster.Pick> picks = entry.slots().stream()
                .filter(EntryRecord.SlotRecord::filled)
                .map(s -> new Roster.Pick(s.slot(), s.playerId(), s.option()))
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

    private <T> T pick(List<T> options, long seed) {
        if (options.isEmpty()) {
            throw new IllegalStateException("wheel has nothing to land on");
        }
        return options.get(new Random(seed).nextInt(options.size()));
    }

    /**
     * A spin's outcome is a function of which spin it is, not of when it happens. Same entry,
     * same slot, same attempt, same result, forever.
     */
    private long seed(String entryId, int slotIndex, String kind, boolean respin) {
        String material = entryId + ":" + slotIndex + ":" + kind + ":" + (respin ? 1 : 0);
        long hash = 1125899906842597L;
        for (byte b : material.getBytes(StandardCharsets.UTF_8)) {
            hash = 31 * hash + b;
        }
        return hash;
    }
}
