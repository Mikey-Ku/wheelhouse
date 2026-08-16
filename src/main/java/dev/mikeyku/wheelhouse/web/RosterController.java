package dev.mikeyku.wheelhouse.web;

import dev.mikeyku.wheelhouse.model.Roster;
import dev.mikeyku.wheelhouse.scoring.ScoringService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scores a roster posted as JSON. Temporary: rosters are not persisted yet, so this exists
 * to exercise scoring against live games without a build flow in front of it.
 */
@RestController
@RequestMapping("/api/roster")
public class RosterController {

    private final ScoringService scoring;

    public RosterController(ScoringService scoring) {
        this.scoring = scoring;
    }

    @PostMapping("/score")
    public ScoringService.ScoredRoster score(@RequestBody Roster roster) {
        return scoring.score(roster);
    }
}
