package dev.mikeyku.wheelhouse.web;

import dev.mikeyku.wheelhouse.model.Player;
import dev.mikeyku.wheelhouse.model.Slot;
import dev.mikeyku.wheelhouse.sleeper.PlayerCatalog;
import dev.mikeyku.wheelhouse.wheel.WheelPool;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of the wheel's inputs. Spinning itself is not built yet: a spin has to be
 * server-authoritative and recorded on first request, otherwise anyone can re-roll by
 * refreshing.
 */
@RestController
@RequestMapping("/api/wheel")
public class WheelController {

    private final WheelPool pool;
    private final PlayerCatalog catalog;

    public WheelController(WheelPool pool, PlayerCatalog catalog) {
        this.pool = pool;
        this.catalog = catalog;
    }

    /** The roster shape: one QB, one RB, two FLEX, and what each slot can harvest. */
    @GetMapping("/slots")
    public List<Map<String, Object>> slots() {
        return Arrays.stream(Slot.values())
                .map(s -> Map.<String, Object>of(
                        "slot", s.name(),
                        "positions", s.positions(),
                        "candidates", pool.candidates(s).size(),
                        "teams", pool.teams(s).size(),
                        "options", s.options().stream()
                                .map(o -> Map.of(
                                        "part", o.label(),
                                        "stat", o.description()))
                                .toList()))
                .toList();
    }

    @GetMapping("/teams")
    public List<String> teams(@RequestParam Slot slot) {
        return pool.teams(slot);
    }

    @GetMapping("/players")
    public List<Map<String, Object>> players(@RequestParam Slot slot,
                                             @RequestParam(required = false) String team) {
        List<Player> players = team == null ? pool.candidates(slot) : pool.candidates(slot, team);
        return players.stream().map(this::describe).toList();
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        return Map.of(
                "activePlayers", catalog.size(),
                "teams", catalog.teams());
    }

    private Map<String, Object> describe(Player p) {
        return Map.of(
                "id", p.id(),
                "espnId", p.espnId() == null ? "" : p.espnId(),
                "name", p.name() == null ? "" : p.name(),
                "team", p.team(),
                "position", p.position(),
                "rank", p.searchRank(),
                "injury", p.injuryStatus() == null ? "" : p.injuryStatus());
    }
}
