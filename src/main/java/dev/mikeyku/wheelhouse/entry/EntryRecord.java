package dev.mikeyku.wheelhouse.entry;

import dev.mikeyku.wheelhouse.model.Slot;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One player's roster for one week. */
@Entity
@Table(name = "entries")
public class EntryRecord {

    @Id
    private String id;

    private String contestId;
    private String owner;
    private Instant createdAt;

    /** Set once all four slots are filled. Null means still building. */
    private Instant submittedAt;

    // Eager on purpose. An entry is never useful without its slots, there are always exactly
    // four of them, and open-in-view is off, so lazy loading would just fail outside the
    // transaction that read the entry.
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "entry_id")
    @OrderBy("slotIndex")
    private List<SlotRecord> slots = new ArrayList<>();

    protected EntryRecord() {
    }

    public EntryRecord(String id, String contestId, String owner, Instant createdAt) {
        this.id = id;
        this.contestId = contestId;
        this.owner = owner;
        this.createdAt = createdAt;
        for (int i = 0; i < dev.mikeyku.wheelhouse.model.Roster.SHAPE.size(); i++) {
            slots.add(new SlotRecord(i, dev.mikeyku.wheelhouse.model.Roster.SHAPE.get(i)));
        }
    }

    public boolean complete() {
        return slots.stream().allMatch(SlotRecord::filled);
    }

    public String id() { return id; }
    public String contestId() { return contestId; }
    public String owner() { return owner; }
    public Instant createdAt() { return createdAt; }
    public Instant submittedAt() { return submittedAt; }
    public void submittedAt(Instant at) { this.submittedAt = at; }
    public List<SlotRecord> slots() { return slots; }

    public SlotRecord slot(int index) {
        return slots.stream().filter(s -> s.slotIndex() == index).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no slot " + index));
    }

    /** One roster position and everything the wheel has decided about it so far. */
    @Entity
    @Table(name = "entry_slots")
    public static class SlotRecord {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private int slotIndex;

        @Enumerated(EnumType.STRING)
        private Slot slot;

        private String team;
        private String playerId;
        private String option;
        private boolean teamRespun;
        private boolean playerRespun;

        protected SlotRecord() {
        }

        SlotRecord(int slotIndex, Slot slot) {
            this.slotIndex = slotIndex;
            this.slot = slot;
        }

        public boolean filled() {
            return team != null && playerId != null && option != null;
        }

        public int slotIndex() { return slotIndex; }
        public Slot slot() { return slot; }
        public String team() { return team; }
        public void team(String team) { this.team = team; }
        public String playerId() { return playerId; }
        public void playerId(String playerId) { this.playerId = playerId; }
        public String option() { return option; }
        public void option(String option) { this.option = option; }
        public boolean teamRespun() { return teamRespun; }
        public void teamRespun(boolean v) { this.teamRespun = v; }
        public boolean playerRespun() { return playerRespun; }
        public void playerRespun(boolean v) { this.playerRespun = v; }
    }
}
