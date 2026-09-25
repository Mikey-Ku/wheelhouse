package dev.mikeyku.wheelhouse.account;

import java.security.SecureRandom;
import java.util.Base64;

/** Session tokens. Passwords are Supabase's now; the session cookie is still ours. */
final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {
    }

    /** A random token for a session cookie: 256 bits, URL-safe. */
    static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
