package dev.mikeyku.wheelhouse.entry;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An append-only record of every spin that has ever happened.
 *
 * <p>This is what stops someone re-rolling by refreshing. A spin is written the first time
 * it is requested and never rewritten, so the roster is derivable from the log rather than
 * from whatever the client claims. It is also the audit trail if a result is ever disputed.
 */
@Entity
@Table(name = "spins")
public class SpinRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String entryId;
    private int slotIndex;

    /** TEAM or PLAYER. */
    private String kind;

    private String result;
    private boolean respin;
    private Instant at;

    protected SpinRecord() {
    }

    public SpinRecord(String entryId, int slotIndex, String kind, String result,
                      boolean respin, Instant at) {
        this.entryId = entryId;
        this.slotIndex = slotIndex;
        this.kind = kind;
        this.result = result;
        this.respin = respin;
        this.at = at;
    }

    public String entryId() { return entryId; }
    public int slotIndex() { return slotIndex; }
    public String kind() { return kind; }
    public String result() { return result; }
    public boolean respin() { return respin; }
    public Instant at() { return at; }
}
