package dev.mikeyku.wheelhouse.web;

import dev.mikeyku.wheelhouse.contest.ContestService;
import dev.mikeyku.wheelhouse.espn.EspnClient;
import dev.mikeyku.wheelhouse.ingest.GamePoller;
import dev.mikeyku.wheelhouse.ingest.IngestService;
import dev.mikeyku.wheelhouse.model.GameSnapshot;
import dev.mikeyku.wheelhouse.model.StatDelta;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A window into the ingestion pipeline while it runs. Temporary scaffolding, not the
 * eventual public API, but it is how you confirm the parser and the differ are correct
 * against a real live game.
 */
@RestController
@RequestMapping("/api")
public class IngestController {

    private final GamePoller poller;
    private final IngestService ingest;
    private final ContestService contests;

    public IngestController(GamePoller poller, IngestService ingest, ContestService contests) {
        this.poller = poller;
        this.ingest = ingest;
        this.contests = contests;
    }

    @GetMapping("/live")
    public List<EspnClient.GameRef> live() {
        return poller.currentLiveGames();
    }

    @GetMapping("/deltas")
    public List<Map<String, Object>> deltas(@RequestParam(defaultValue = "50") int limit) {
        List<StatDelta> all = ingest.recentDeltas();
        return all.reversed().stream()
                .limit(limit)
                .map(d -> Map.<String, Object>of(
                        "player", nameOf(d),
                        "stat", d.key().category() + "." + d.key().stat(),
                        "previous", d.previous() == null ? "new" : d.previous(),
                        "current", d.current(),
                        "change", d.change(),
                        "at", d.observedAt().toString()))
                .toList();
    }

    /** Every stat we are currently tracking for one game, biggest numbers first. */
    @GetMapping("/games/{eventId}")
    public List<Map<String, Object>> game(@PathVariable String eventId) {
        GameSnapshot snapshot = ingest.snapshot(contests.current().id(), eventId);
        if (snapshot == null) {
            return List.of();
        }
        return snapshot.stats().entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<dev.mikeyku.wheelhouse.model.StatKey, Double> e)
                        -> e.getValue()).reversed())
                .map(e -> Map.<String, Object>of(
                        "player", snapshot.athleteNames().getOrDefault(e.getKey().athleteId(), e.getKey().athleteId()),
                        "team", snapshot.athleteTeams().getOrDefault(e.getKey().athleteId(), "?"),
                        "stat", e.getKey().category() + "." + e.getKey().stat(),
                        "value", e.getValue()))
                .toList();
    }

    private String nameOf(StatDelta delta) {
        GameSnapshot snapshot = ingest.snapshot(contests.current().id(), delta.eventId());
        if (snapshot == null) {
            return delta.key().athleteId();
        }
        return snapshot.athleteNames().getOrDefault(delta.key().athleteId(), delta.key().athleteId());
    }
}
