package dev.mikeyku.wheelhouse.web;

import dev.mikeyku.wheelhouse.account.AccountService;
import dev.mikeyku.wheelhouse.account.Identities;
import dev.mikeyku.wheelhouse.account.UserRecord;
import dev.mikeyku.wheelhouse.entry.EntryRecord;
import dev.mikeyku.wheelhouse.entry.EntryRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Sign in, sign out, and the profile page's data.
 *
 * <p>The page signs in with Supabase (Google, or an email and password) and hands the access
 * token over once; this swaps it for the app's own session cookie. Tokens only ever travel in a
 * request body. One in a query string ends up in server logs, browser history and any proxy on
 * the way, so none of these endpoints read one.
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AccountService accounts;
    private final Identities identities;
    private final EntryRepository entries;
    private final Standings standings;
    private final Map<String, Object> authConfig;

    public AccountController(AccountService accounts, Identities identities, EntryRepository entries,
                             Standings standings,
                             @Value("${wheelhouse.supabase.url:}") String supabaseUrl,
                             @Value("${wheelhouse.supabase.key:}") String supabaseKey) {
        this.accounts = accounts;
        this.identities = identities;
        this.entries = entries;
        this.standings = standings;
        this.authConfig = supabaseUrl.isBlank() || supabaseKey.isBlank()
                ? Map.of()
                : Map.of("url", supabaseUrl.strip(), "key", supabaseKey.strip());
    }

    public record Token(String accessToken) {}

    public record Name(String name) {}

    public record Ids(List<String> ids) {}

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        return describe(accounts.current(request));
    }

    /**
     * Play without a profile. Idempotent: a browser that already has a session, guest or not,
     * keeps it.
     */
    @PostMapping("/guest")
    public Map<String, Object> guest(HttpServletRequest request, HttpServletResponse response) {
        UserRecord current = accounts.current(request);
        if (current != null) {
            return describe(current);
        }
        String token = accounts.guest();
        setCookie(request, response, token, AccountService.GUEST_LENGTH);
        return describe(accounts.current(withToken(request, token)));
    }

    /**
     * What the page needs to start a Supabase sign-in: the project URL and its publishable key.
     * Both are public by design; what keeps the database closed is {@code DataApiLockdown}.
     * Empty when this server has no Supabase project, and the page says sign-in isn't set up.
     */
    @GetMapping("/auth-config")
    public Map<String, Object> authConfig() {
        return authConfig;
    }

    /** A Supabase access token, exchanged for this app's session. */
    @PostMapping("/supabase")
    public Map<String, Object> signIn(@RequestBody Token body, HttpServletRequest request,
                                      HttpServletResponse response) {
        Identities.Identity identity = identities.verify(body.accessToken());
        String token = accounts.signIn(identity, accounts.current(request));
        setCookie(request, response, token, AccountService.SESSION_LENGTH);
        return describe(accounts.current(withToken(request, token)));
    }

    /** Checked before an email sign-up, so a taken name is caught before an account exists. */
    @PostMapping("/name-check")
    public Map<String, Object> checkName(@RequestBody Name body) {
        return Map.of("name", accounts.checkName(body.name()));
    }

    @PostMapping("/signout")
    public Map<String, Object> signOut(HttpServletRequest request, HttpServletResponse response) {
        accounts.signOut(AccountService.token(request));
        setCookie(request, response, "", Duration.ZERO);
        return describe(null);
    }

    @PostMapping("/name")
    public Map<String, Object> rename(@RequestBody Name body, HttpServletRequest request) {
        return describe(accounts.rename(accounts.requireProfile(request), body.name()));
    }

    /** Hands over rosters this browser played before it had a profile. */
    @PostMapping("/claim")
    public Map<String, Object> claim(@RequestBody Ids body, HttpServletRequest request) {
        UserRecord user = accounts.requireProfile(request);
        int n = body.ids() == null ? 0 : accounts.claim(user, body.ids());
        return Map.of("claimed", n);
    }

    /** Every roster on this profile, newest first, with a few totals across them. */
    @GetMapping("/rosters")
    public Map<String, Object> rosters(HttpServletRequest request) {
        UserRecord user = accounts.require(request);
        List<EntryRecord> mine = entries.findByUserId(user.id()).stream()
                .sorted(Comparator.comparing(EntryRecord::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        List<Map<String, Object>> rows = mine.stream().map(standings::summary).toList();

        List<Map<String, Object>> finals = rows.stream()
                .filter(r -> "final".equals(r.get("status"))).toList();
        Integer best = finals.stream()
                .map(r -> r.get("standing"))
                .filter(Objects::nonNull)
                .map(s -> (Integer) ((Map<?, ?>) s).get("rank"))
                .min(Integer::compare).orElse(null);
        double avgCapture = finals.stream()
                .map(r -> (Integer) r.get("capturePercent"))
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue).average().orElse(Double.NaN);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", user.name());
        out.put("guest", user.guest());
        out.put("played", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("complete"))).count());
        out.put("bestRank", best);
        out.put("avgCapture", Double.isNaN(avgCapture) ? null : (int) Math.round(avgCapture));
        out.put("rosters", rows);
        return out;
    }

    private Map<String, Object> describe(UserRecord user) {
        Map<String, Object> m = new LinkedHashMap<>();
        // A guest has a session but no profile. The page treats "signedIn" as "has a profile".
        m.put("signedIn", user != null && !user.guest());
        m.put("guest", user != null && user.guest());
        if (user != null) {
            m.put("name", user.name());
        }
        return m;
    }

    /**
     * HttpOnly so page scripts cannot read it, SameSite=Lax so another site cannot post with it,
     * and Secure whenever the request came in over HTTPS. Locally it is plain HTTP and a Secure
     * cookie would never be sent back.
     */
    private void setCookie(HttpServletRequest request, HttpServletResponse response,
                           String token, Duration maxAge) {
        boolean https = request.isSecure()
                || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        ResponseCookie cookie = ResponseCookie.from(AccountService.COOKIE, token)
                .httpOnly(true)
                .secure(https)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** The request as it will look once the browser sends the cookie just set. */
    private HttpServletRequest withToken(HttpServletRequest request, String token) {
        return new jakarta.servlet.http.HttpServletRequestWrapper(request) {
            @Override
            public jakarta.servlet.http.Cookie[] getCookies() {
                return new jakarta.servlet.http.Cookie[] {
                        new jakarta.servlet.http.Cookie(AccountService.COOKIE, token)};
            }
        };
    }
}
