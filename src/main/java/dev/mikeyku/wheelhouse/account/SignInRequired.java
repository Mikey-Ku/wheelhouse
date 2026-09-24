package dev.mikeyku.wheelhouse.account;

/** The request needs a profile and came without one. Answered as 401. */
public class SignInRequired extends RuntimeException {

    public SignInRequired() {
        super("Sign in to play.");
    }
}
