package dev.mikeyku.wheelhouse.scoring;

import dev.mikeyku.wheelhouse.ingest.IngestService;
import dev.mikeyku.wheelhouse.model.Player;
import dev.mikeyku.wheelhouse.model.Roster;
import dev.mikeyku.wheelhouse.model.Slot;
import dev.mikeyku.wheelhouse.sleeper.AthleteResolver;
import dev.mikeyku.wheelhouse.sleeper.PlayerCatalog;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Turns a roster into a number.
 *
 * <p>Pure function of the current stat readings: the same roster against the same stats
 * always scores the same. Nothing is accumulated and nothing is cached, which is what will
 * let a stat correction be handled by simply scoring again.
 */
@Service
public class ScoringService {

    private final PlayerCatalog catalog;
    private final AthleteResolver resolver;
    private final IngestService ingest;
    private final ScoringConfig config;

    public ScoringService(PlayerCatalog catalog, AthleteResolver resolver,
                          IngestService ingest, ScoringConfig config) {
        this.catalog = catalog;
        this.resolver = resolver;
        this.ingest = ingest;
        this.config = config;
    }

    public record ScoredPick(
            Slot slot,
            String playerId,
            String playerName,
            String team,
            String part,
            String stat,
            Double raw,
            double multiplier,
            double points,
            String note) {}

    public record ScoredRoster(String rosterId, String owner, List<ScoredPick> picks, double total) {}

    public ScoredRoster score(Roster roster) {
        List<ScoredPick> scored = roster.picks().stream()
                .map(pick -> scorePick(roster.contestId(), pick))
                .toList();
        double total = scored.stream().mapToDouble(ScoredPick::points).sum();
        return new ScoredRoster(roster.id(), roster.owner(), scored, round(total));
    }

    private ScoredPick scorePick(String contestId, Roster.Pick pick) {
        Player player = catalog.byId(pick.playerId());
        if (player == null) {
            return empty(pick, null, "unknown player");
        }

        Slot.StatOption option = pick.slot().option(pick.option()).orElse(null);
        if (option == null) {
            return empty(pick, player, "no such option for " + pick.slot());
        }
        String espnId = resolver.espnIdFor(player);
        if (espnId == null) {
            return empty(pick, player, "not yet seen in any box score");
        }

        Double raw = ingest.statValue(contestId, option.keyFor(espnId));
        double multiplier = config.multiplier(pick.slot(), option);

        // A stat absent from the box score means the player has not recorded one yet, which
        // is a legitimate zero rather than missing data. Everyone appears in the box score
        // once their game is live.
        double value = raw == null ? 0.0 : raw;

        return new ScoredPick(
                pick.slot(), player.id(), player.name(), player.team(),
                option.label(), option.category() + "." + option.stat(),
                raw, multiplier, round(value * multiplier),
                raw == null ? "not yet playing" : "");
    }

    private ScoredPick empty(Roster.Pick pick, Player player, String note) {
        return new ScoredPick(
                pick.slot(), pick.playerId(),
                player == null ? null : player.name(),
                player == null ? null : player.team(),
                pick.option(), null, null, 0, 0, note);
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
