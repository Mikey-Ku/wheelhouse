package dev.mikeyku.wheelhouse.account;

import dev.mikeyku.wheelhouse.entry.EntryRecord;
import dev.mikeyku.wheelhouse.entry.EntryRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Profiles and the sessions that remember them.
 *
 * <p>Hosted locally for now, so this is deliberately the smallest thing that is still honest:
 * a name and a password, PBKDF2 at rest, a random session token in an HttpOnly cookie. When the
 * app moves to a host this is the piece to swap for a real identity provider; nothing outside
 * this package knows how a user was established, only who they are.
 */
@Service
public class AccountService {

    public static final String COOKIE = "wh_session";
    public static final Duration SESSION_LENGTH = Duration.ofDays(180);
    /** How long a guest browser stays itself. After that its rosters are orphaned in the database. */
    public static final Duration GUEST_LENGTH = Duration.ofDays(30);

    /** Letters, numbers, spaces and a little punctuation. It is printed on public boards. */
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} _.'-]{2,20}");
    private static final int MIN_PASSWORD = 6;

    /** Wrong passwords per name before it has to wait, and how long the wait is. */
    private static final int MAX_FAILURES = 8;
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    private final UserRepository users;
    private final SessionRepository sessions;
    private final EntryRepository entries;
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    private record Failures(int count, Instant since) {}

    public AccountService(UserRepository users, SessionRepository sessions, EntryRepository entries) {
        this.users = users;
        this.sessions = sessions;
        this.entries = entries;
    }

    /**
     * Somebody who wants to play without making a profile first. They get a session and a
     * throwaway name; their rosters are kept and stay off the leaderboards until they make one.
     */
    @Transactional
    public String guest() {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        UserRecord user = users.save(UserRecord.guest(UUID.randomUUID().toString(), "Guest " + tag, Instant.now()));
        return openSession(user, GUEST_LENGTH);
    }

    /**
     * A new profile, already signed in. A guest making one keeps everything they played: the
     * guest record becomes the profile rather than being replaced by it.
     */
    @Transactional
    public String signUp(String name, String password, UserRecord current) {
        String clean = cleanName(name);
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new IllegalArgumentException("Password needs at least " + MIN_PASSWORD + " characters.");
        }
        if (users.findByNameKey(UserRecord.key(clean)).isPresent()) {
            throw new IllegalArgumentException("That name is taken.");
        }
        UserRecord user;
        if (current != null && current.guest()) {
            current.becomeProfile(clean, Passwords.hash(password));
            user = users.save(current);
            adopt(user, user);
        } else {
            user = users.save(new UserRecord(
                    UUID.randomUUID().toString(), clean, Passwords.hash(password), Instant.now()));
        }
        return openSession(user);
    }

    @Transactional
    public String signIn(String name, String password, UserRecord current) {
        String key = UserRecord.key(name == null ? "" : name);
        Failures f = failures.get(key);
        if (f != null && f.count() >= MAX_FAILURES
                && Instant.now().isBefore(f.since().plus(LOCKOUT))) {
            throw new IllegalArgumentException("Too many tries. Wait 15 minutes.");
        }
        UserRecord user = users.findByNameKey(key).orElse(null);
        if (user == null || !Passwords.matches(password, user.passwordHash())) {
            failures.merge(key, new Failures(1, Instant.now()), (old, one) ->
                    Instant.now().isAfter(old.since().plus(LOCKOUT))
                            ? one : new Failures(old.count() + 1, old.since()));
            // The same message either way, so a sign-in form cannot be used to list names.
            throw new IllegalArgumentException("Wrong name or password.");
        }
        failures.remove(key);
        // Signing in from a guest session brings what the guest played along.
        if (current != null && current.guest() && !current.id().equals(user.id())) {
            adopt(current, user);
            users.delete(current);
        }
        return openSession(user);
    }

    @Transactional
    public void signOut(String token) {
        if (token != null) {
            sessions.deleteById(token);
        }
    }

    /** Who is signed in on this request, or null. */
    public UserRecord current(HttpServletRequest request) {
        String token = token(request);
        if (token == null) {
            return null;
        }
        SessionRecord session = sessions.findById(token).orElse(null);
        if (session == null || Instant.now().isAfter(session.expiresAt())) {
            return null;
        }
        return users.findById(session.userId()).orElse(null);
    }

    /** Every roster the guest played, moved onto the profile and onto the leaderboards. */
    private void adopt(UserRecord from, UserRecord to) {
        for (EntryRecord entry : entries.findByUserId(from.id())) {
            entry.userId(to.id());
            entry.owner(to.name());
            entry.guest(false);
            entries.save(entry);
        }
    }

    /** Like {@link #current} but refuses to continue without somebody. */
    public UserRecord require(HttpServletRequest request) {
        UserRecord user = current(request);
        if (user == null) {
            throw new SignInRequired();
        }
        return user;
    }

    /** A real profile, not a guest: for the things only a profile can do, like a name. */
    public UserRecord requireProfile(HttpServletRequest request) {
        UserRecord user = require(request);
        if (user.guest()) {
            throw new SignInRequired("Make a profile first.");
        }
        return user;
    }

    /**
     * Changes the name everywhere it is shown. Rosters carry the name they were played under,
     * so they are renamed with the profile rather than left pointing at an old one.
     */
    @Transactional
    public UserRecord rename(UserRecord user, String name) {
        String clean = cleanName(name);
        UserRecord taken = users.findByNameKey(UserRecord.key(clean)).orElse(null);
        if (taken != null && !taken.id().equals(user.id())) {
            throw new IllegalArgumentException("That name is taken.");
        }
        user.name(clean);
        users.save(user);
        for (EntryRecord entry : entries.findByUserId(user.id())) {
            entry.owner(clean);
            entries.save(entry);
        }
        return user;
    }

    /**
     * Rosters this browser played before profiles existed, handed over to the profile now
     * signing in. Only rosters nobody owns yet: holding an id proves you played it, but it
     * cannot take a roster from a profile that already has it.
     */
    @Transactional
    public int claim(UserRecord user, List<String> entryIds) {
        int claimed = 0;
        for (String id : entryIds.stream().distinct().limit(200).toList()) {
            EntryRecord entry = entries.findById(id).orElse(null);
            if (entry != null && entry.userId() == null) {
                entry.userId(user.id());
                entry.owner(user.name());
                entry.guest(user.guest());
                entries.save(entry);
                claimed++;
            }
        }
        return claimed;
    }

    public static String token(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName()) && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String openSession(UserRecord user) {
        return openSession(user, SESSION_LENGTH);
    }

    private String openSession(UserRecord user, Duration length) {
        String token = Passwords.token();
        Instant now = Instant.now();
        sessions.save(new SessionRecord(token, user.id(), now, now.plus(length)));
        return token;
    }

    private String cleanName(String name) {
        String clean = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (!NAME.matcher(clean).matches()) {
            throw new IllegalArgumentException(
                    "Names are 2 to 20 letters, numbers or spaces.");
        }
        return clean;
    }
}
