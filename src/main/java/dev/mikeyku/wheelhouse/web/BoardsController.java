package dev.mikeyku.wheelhouse.web;

import dev.mikeyku.wheelhouse.account.AccountService;
import dev.mikeyku.wheelhouse.account.UserRecord;
import dev.mikeyku.wheelhouse.contest.ArchiveService;
import dev.mikeyku.wheelhouse.contest.Contest;
import dev.mikeyku.wheelhouse.contest.ContestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The leaderboard pages: which boards exist, and one board's standings. */
@RestController
@RequestMapping("/api/boards")
public class BoardsController {

    private final Standings standings;
    private final AccountService accounts;
    private final ContestService contests;
    private final ArchiveService archive;

    public BoardsController(Standings standings, AccountService accounts, ContestService contests,
                            ArchiveService archive) {
        this.standings = standings;
        this.accounts = accounts;
        this.contests = contests;
        this.archive = archive;
    }

    @GetMapping
    public List<Map<String, Object>> boards() {
        return standings.boards();
    }

    /**
     * One board. Your own row carries your roster's id so the page can link to it; nobody
     * else's does.
     */
    @GetMapping("/one")
    public Map<String, Object> one(@RequestParam String contestId,
                                   @RequestParam(required = false) String slate,
                                   HttpServletRequest request) {
        Contest c = contests.byId(contestId);
        if (c == null) {
            throw new IllegalArgumentException("That board doesn't exist.");
        }
        // An archived week's totals are computed from its box scores, which may have been
        // released from memory since anyone last played it.
        if (c.archive()) {
            try {
                archive.load(c.season(), c.week());
            } catch (RuntimeException e) {
                // Rows still list; totals may read zero until ESPN answers.
            }
        }
        UserRecord me = accounts.current(request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contestId", contestId);
        out.put("slate", slate);
        out.put("label", standings.boardLabel(contestId, slate));
        out.put("archive", c.archive());
        out.put("rows", standings.board(contestId, blankToNull(slate), me == null ? null : me.id()));
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() || s.equals("null") ? null : s;
    }
}
