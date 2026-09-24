package dev.mikeyku.wheelhouse.account;

/** Somebody else's roster. Answered as 403. */
public class NotYours extends RuntimeException {

    public NotYours() {
        super("That roster belongs to someone else.");
    }
}
