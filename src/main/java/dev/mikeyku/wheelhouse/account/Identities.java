package dev.mikeyku.wheelhouse.account;

/**
 * Turns a Supabase access token into the person it was issued to. This is the only question the
 * app asks of Supabase Auth: once it has an answer, the session is the app's own cookie, and
 * nothing past this interface knows whether that person came from Google or an email address.
 */
public interface Identities {

    /** Who the token belongs to. Throws {@link SignInRequired} for a token Supabase does not vouch for. */
    Identity verify(String accessToken);

    /** A Supabase user id, and the name to offer them the first time they sign in. May be blank. */
    record Identity(String id, String suggestedName) {}
}
