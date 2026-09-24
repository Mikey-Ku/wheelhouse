package dev.mikeyku.wheelhouse.account;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One signed-in browser. The token is random, lives only in an HttpOnly cookie, and is the
 * whole credential, so it is never logged and never returned in a response body.
 */
@Entity
@Table(name = "sessions")
public class SessionRecord {

    @Id
    private String token;

    private String userId;
    private Instant createdAt;
    private Instant expiresAt;

    protected SessionRecord() {
    }

    public SessionRecord(String token, String userId, Instant createdAt, Instant expiresAt) {
        this.token = token;
        this.userId = userId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String token() { return token; }
    public String userId() { return userId; }
    public Instant expiresAt() { return expiresAt; }
}
