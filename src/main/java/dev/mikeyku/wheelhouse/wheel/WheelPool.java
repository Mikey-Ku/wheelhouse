package dev.mikeyku.wheelhouse.wheel;

import dev.mikeyku.wheelhouse.contest.ArchiveRoster;
import dev.mikeyku.wheelhouse.model.Player;
import dev.mikeyku.wheelhouse.model.Slot;
import dev.mikeyku.wheelhouse.sleeper.PlayerCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Decides who is allowed to appear on the wheel.
 *
 * <p>This is the single most important knob in the game. Without a relevance filter the
 * wheel is full of practice squad players nobody recognises, and the game is unplayable.
 * With too tight a filter every roster looks the same. {@code searchRank} is Sleeper's own
 * relevance ordering and is the cheapest good proxy we have.
 */
@Service
public class WheelPool {

    private final PlayerCatalog catalog;
    private final ArchiveRoster archive;
    private final int rankCutoff;

    public WheelPool(PlayerCatalog catalog, ArchiveRoster archive,
                     @Value("${wheelhouse.wheel.rank-cutoff:400}") int rankCutoff) {
        this.catalog = catalog;
        this.archive = archive;
        this.rankCutoff = rankCutoff;
    }

    /**
     * Routes to the right universe for the week. A live week draws on Sleeper's current
     * roster; an archived week draws on whoever actually played that Sunday.
     */
    public List<Player> candidates(String contestId, Slot slot) {
        return isArchive(contestId) ? archive.candidates(contestId, slot) : candidates(slot);
    }

    public List<Player> candidates(String contestId, Slot slot, String team) {
        return isArchive(contestId)
                ? archive.candidates(contestId, slot, team)
                : candidates(slot, team);
    }

    public List<String> teams(String contestId, Slot slot) {
        return isArchive(contestId) ? archive.teams(contestId, slot) : teams(slot);
    }

    private boolean isArchive(String contestId) {
        return contestId != null && contestId.startsWith("a");
    }

    /** Everyone eligible for this slot, league wide, most relevant first. */
    public List<Player> candidates(Slot slot) {
        return catalog.all().stream()
                .filter(Player::rostered)
                .filter(slot::accepts)
                .filter(p -> p.searchRank() < rankCutoff)
                .sorted(Comparator.comparingInt(Player::searchRank))
                .toList();
    }

    /** Everyone eligible for this slot on one team. This is what a team spin resolves to. */
    public List<Player> candidates(Slot slot, String team) {
        return candidates(slot).stream()
                .filter(p -> p.team().equalsIgnoreCase(team))
                .toList();
    }

    /**
     * Teams the wheel may land on for this slot. A team with no eligible player would be a
     * dead spin, so it is never offered.
     */
    public List<String> teams(Slot slot) {
        return candidates(slot).stream()
                .map(Player::team)
                .distinct()
                .sorted()
                .toList();
    }
}
