package dev.mikeyku.wheelhouse.scoring;

import dev.mikeyku.wheelhouse.ingest.IngestService;
import dev.mikeyku.wheelhouse.model.Player;
import dev.mikeyku.wheelhouse.model.Roster;
import dev.mikeyku.wheelhouse.model.Slot;
import dev.mikeyku.wheelhouse.projection.ProjectionService;
import dev.mikeyku.wheelhouse.sleeper.AthleteResolver;
import dev.mikeyku.wheelhouse.sleeper.PlayerCatalog;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Turns a roster into two numbers: what was forecast, and what happened.
 *
 * <p>Pure function of the current readings. The same roster against the same stats always
 * scores the same, nothing is accumulated and nothing is cached, which is what will let a
 * stat correction be handled by simply scoring again.
 *
 * <p>The two sides come from different places on purpose. Actuals are ESPN box score values
 * reached by athlete id; projections are Sleeper forecasts reached by Sleeper id or by name.
 * Both are converted to points by the same multipliers, so the comparison is honest.
 */
@Service
public class ScoringService {

    private final PlayerCatalog catalog;
    private final AthleteResolver resolver;
    private final IngestService ingest;
    private final ProjectionService projections;
    private final ScoringConfig config;

    public ScoringService(PlayerCatalog catalog, AthleteResolver resolver, IngestService ingest,
                          ProjectionService projections, ScoringConfig config) {
        this.catalog = catalog;
        this.resolver = resolver;
        this.ingest = ingest;
        this.projections = projections;
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
            Double projectedRaw,
            double multiplier,
            double points,
            double projectedPoints,
            String note) {}

    public record ScoredRoster(String rosterId, String owner, List<ScoredPick> picks,
                               double total, double projectedTotal) {}

    public ScoredRoster score(Roster roster) {
        List<ScoredPick> scored = roster.picks().stream()
                .map(pick -> scorePick(roster.contestId(), pick))
                .toList();
        return new ScoredRoster(
                roster.id(), roster.owner(), scored,
                round(scored.stream().mapToDouble(ScoredPick::points).sum()),
                round(scored.stream().mapToDouble(ScoredPick::projectedPoints).sum()));
    }

    /** The points-per-unit for a part, so the UI can show its own arithmetic. */
    public double multiplierFor(Slot slot, Slot.StatOption option) {
        return config.multiplier(slot, option);
    }

    /** One player against one stat, priced both ways. */
    public ScoredPick scoreOne(String contestId, Slot slot, Player player, Slot.StatOption option) {
        double multiplier = config.multiplier(slot, option);

        Double projectedRaw = projections.projected(contestId, player, option);

        // Actuals need an ESPN athlete id, which is only known once the player has appeared in
        // a box score. Before kickoff that is nobody, and that is fine: the absence of a result
        // is the normal state while a week is being built, not an error.
        String espnId = resolver.espnIdFor(player);
        Double raw = espnId == null ? null : actual(contestId, espnId, option);

        return new ScoredPick(
                slot, player.id(), player.name(), player.team(),
                option.label(), option.description(),
                raw, projectedRaw, multiplier,
                round((raw == null ? 0.0 : raw) * multiplier),
                round((projectedRaw == null ? 0.0 : projectedRaw) * multiplier),
                raw == null ? "no result yet" : "");
    }

    /**
     * Sums a part's components. Null only when the box score has none of them at all, which
     * means the player has not been seen yet; a player who took the field but did not record
     * the stat is a real zero.
     */
    private Double actual(String contestId, String espnId, Slot.StatOption option) {
        Double total = null;
        for (Slot.StatRef ref : option.stats()) {
            Double value = ingest.statValue(contestId, ref.keyFor(espnId));
            if (value != null) {
                total = (total == null ? 0.0 : total) + value;
            }
        }
        return total;
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
        return scoreOne(contestId, pick.slot(), player, option);
    }

    private ScoredPick empty(Roster.Pick pick, Player player, String note) {
        return new ScoredPick(
                pick.slot(), pick.playerId(),
                player == null ? null : player.name(),
                player == null ? null : player.team(),
                pick.option(), null, null, null, 0, 0, 0, note);
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
