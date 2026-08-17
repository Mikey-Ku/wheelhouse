package dev.mikeyku.wheelhouse.web;

import dev.mikeyku.wheelhouse.contest.ArchiveService;
import dev.mikeyku.wheelhouse.contest.Contest;
import dev.mikeyku.wheelhouse.entry.EntryRecord;
import dev.mikeyku.wheelhouse.entry.EntryService;
import dev.mikeyku.wheelhouse.ingest.IngestService;
import dev.mikeyku.wheelhouse.model.GameSnapshot;
import dev.mikeyku.wheelhouse.model.Player;
import dev.mikeyku.wheelhouse.model.Slot;
import dev.mikeyku.wheelhouse.model.StatKey;
import dev.mikeyku.wheelhouse.projection.ProjectionService;
import dev.mikeyku.wheelhouse.quant.ResidualModel;
import dev.mikeyku.wheelhouse.quant.RosterPricer;
import dev.mikeyku.wheelhouse.scoring.ScoringService;
import dev.mikeyku.wheelhouse.sleeper.AthleteResolver;
import dev.mikeyku.wheelhouse.sleeper.PlayerCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pricing workbench. Deliberately not linked from the game.
 *
 * <p>This exists to answer the question the composite concept raises but the game itself never
 * asks: if you had to put a number on an assembled roster, what would it be, and how wide
 * would the spread have to be to survive the correlation between its parts? It is analysis, not
 * a market, and there is nothing to stake on it.
 */
@RestController
@RequestMapping("/api/quant")
public class QuantController {

    private static final Logger log = LoggerFactory.getLogger(QuantController.class);

    private final ArchiveService archive;
    private final IngestService ingest;
    private final ProjectionService projections;
    private final PlayerCatalog catalog;
    private final AthleteResolver resolver;
    private final EntryService entries;
    private final ScoringService scoring;
    private final RosterPricer pricer;

    private final ResidualModel residuals = new ResidualModel();
    private final List<String> calibratedWeeks = new ArrayList<>();

    public QuantController(ArchiveService archive, IngestService ingest,
                           ProjectionService projections, PlayerCatalog catalog,
                           AthleteResolver resolver, EntryService entries,
                           ScoringService scoring, RosterPricer pricer) {
        this.archive = archive;
        this.ingest = ingest;
        this.projections = projections;
        this.catalog = catalog;
        this.resolver = resolver;
        this.entries = entries;
        this.scoring = scoring;
        this.pricer = pricer;
    }

    /**
     * Learns how wrong projections usually are, by replaying finished weeks and comparing each
     * forecast to what happened. Every week added is more evidence; a handful is not enough.
     */
    @PostMapping("/calibrate")
    public Map<String, Object> calibrate(@RequestParam int season,
                                         @RequestParam(defaultValue = "1") int fromWeek,
                                         @RequestParam(defaultValue = "6") int toWeek) {
        int added = 0;
        for (int week = fromWeek; week <= toWeek; week++) {
            try {
                Contest contest = archive.load(season, week);
                harvest(contest);
                calibratedWeeks.add(contest.id());
                added++;
            } catch (Exception e) {
                log.warn("calibration skipped {} week {}: {}", season, week, e.toString());
            }
        }
        residuals.fitAll();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("weeksAdded", added);
        out.put("weeksTotal", calibratedWeeks.size());
        out.put("observations", residuals.totalObservations());
        out.put("fits", residuals.all().values().stream()
                .sorted((a, b) -> b.samples() - a.samples())
                .map(f -> Map.of(
                        "stat", f.stat(),
                        "kind", f.count() ? "count" : "continuous",
                        "samples", f.samples(),
                        "meanRatio", round(f.meanRatio()),
                        "sdRatio", round(f.sdRatio()),
                        "zeroRate", round(f.zeroRate()),
                        "usable", f.usable()))
                .toList());
        return out;
    }

    /** Every projection in a finished week, paired with what the box score actually recorded. */
    private void harvest(Contest contest) {
        for (Slot slot : Slot.values()) {
            for (Slot.StatOption option : slot.options()) {
                if (option.stats().size() != 1) {
                    continue;
                }
                Slot.StatRef ref = option.stats().get(0);
                for (Player player : catalog.all()) {
                    if (!slot.accepts(player)) {
                        continue;
                    }
                    Double projected = projections.projected(contest.id(), player, option);
                    String espnId = resolver.espnIdFor(player);
                    if (projected == null || projected <= 0 || espnId == null) {
                        continue;
                    }
                    Double actual = ingest.statValue(contest.id(), ref.keyFor(espnId));
                    if (actual != null) {
                        residuals.observe(ref.stat(), projected, actual);
                    }
                }
            }
        }
    }

    @GetMapping("/model")
    public Map<String, Object> model() {
        return Map.of(
                "weeks", calibratedWeeks,
                "observations", residuals.totalObservations(),
                "fitted", residuals.all().size());
    }

    /**
     * Prices a real roster: the distribution of what it might score, and the line that splits
     * those outcomes in half.
     */
    @GetMapping("/price/{entryId}")
    public Map<String, Object> price(@PathVariable String entryId,
                                     @RequestParam(defaultValue = "20000") int trials) {
        EntryRecord entry = entries.byId(entryId);
        if (entry == null) {
            return Map.of("error", "no such entry");
        }

        List<RosterPricer.Leg> legs = new ArrayList<>();
        for (EntryRecord.PickRecord pick : entry.picks()) {
            if (!pick.filled()) {
                continue;
            }
            Player player = catalog.byId(pick.playerId());
            Slot.StatOption option = pick.slot().option(pick.option()).orElse(null);
            if (player == null || option == null) {
                continue;
            }
            Double projected = projections.projected(entry.contestId(), player, option);
            legs.add(new RosterPricer.Leg(
                    player.id(), pick.team(), opponentOf(entry.contestId(), pick.team()),
                    pick.slot(), option,
                    projected == null ? 0 : projected,
                    scoring.multiplierFor(pick.slot(), option)));
        }

        RosterPricer.Distribution d = pricer.price(legs, residuals, trials, entryId.hashCode());
        ScoringService.ScoredRoster scored = scoring.score(entries.asRoster(entry));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entryId", entryId);
        out.put("legs", legs.size());
        out.put("projectedTotal", d.projectedTotal());
        out.put("fairLine", d.fairLine());
        out.put("mean", d.mean());
        out.put("sd", d.sd());
        out.put("p05", d.p05());
        out.put("p25", d.p25());
        out.put("p75", d.p75());
        out.put("p95", d.p95());
        out.put("trials", d.trials());
        out.put("notes", d.notes());
        out.put("calibrationWeeks", calibratedWeeks.size());
        if (entry.complete()) {
            // Only once the week has resolved, and only because this is a backtest surface.
            out.put("actualTotal", scored.total());
        }
        return out;
    }

    /** Who the team played that week, read off the box score rather than a schedule table. */
    private String opponentOf(String contestId, String team) {
        if (team == null) {
            return null;
        }
        for (GameSnapshot snapshot : ingest.snapshots(contestId)) {
            List<String> sides = snapshot.athleteTeams().values().stream().distinct().toList();
            if (sides.contains(team)) {
                return sides.stream().filter(t -> !t.equals(team)).findFirst().orElse(null);
            }
        }
        return null;
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
