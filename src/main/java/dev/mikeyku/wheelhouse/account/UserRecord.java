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

    /**
     * Playing without a profile. A guest has a name for the slip but no password, reserves no
     * name, and stays off the leaderboards. Making a profile turns the guest into it.
     */
    private Boolean guest;

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
    public boolean guest() { return Boolean.TRUE.equals(guest); }

    /** A guest's name is display only: it holds no key, so it never blocks a real profile. */
    public static UserRecord guest(String id, String name, Instant createdAt) {
        UserRecord u = new UserRecord();
        u.id = id;
        u.name = name;
        u.guest = true;
        u.createdAt = createdAt;
        return u;
    }

    /** The guest becomes a profile: same id, so every roster it played comes with it. */
    public void becomeProfile(String name, String passwordHash) {
        name(name);
        this.passwordHash = passwordHash;
        this.guest = false;
    }
    public Instant createdAt() { return createdAt; }

    public void name(String name) {
        this.name = name;
        this.nameKey = key(name);
    }

    public static String key(String name) {
        return name == null ? null : name.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
