package dev.mikeyku.wheelhouse.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A profile: the name on the leaderboard and the thing your rosters belong to.
 *
 * <p>Before this, a roster was owned by whoever held its id in their browser's storage, and
 * the name on it was whatever had been typed into it. A cleared browser lost everything, and
 * two rosters by the same person could carry two names.
 */
@Entity
@Table(name = "users")
public class UserRecord {

    @Id
    private String id;

    /** As typed, for display. */
    private String name;

    /** Lowercased, for lookup and uniqueness. "Mikey" and "mikey" are the same profile. */
    @Column(unique = true)
    private String nameKey;

    private String passwordHash;
    private Instant createdAt;

    protected UserRecord() {
    }

    public UserRecord(String id, String name, String passwordHash, Instant createdAt) {
        this.id = id;
        name(name);
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String passwordHash() { return passwordHash; }
    public Instant createdAt() { return createdAt; }

    public void name(String name) {
        this.name = name;
        this.nameKey = key(name);
    }

    public static String key(String name) {
        return name == null ? null : name.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
