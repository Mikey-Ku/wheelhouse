package dev.mikeyku.wheelhouse.entry;

import dev.mikeyku.wheelhouse.model.Roster;
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

/** One player's roster for one week: four composite positions, fourteen picks. */
@Entity
@Table(name = "entries")
public class EntryRecord {

    @Id
    private String id;

    private String contestId;
    private String owner;
    private Instant createdAt;

    /** Set once every pick is filled. Null means still building. */
    private Instant submittedAt;

    /**
     * Respins are a budget for the whole roster rather than one per pick. With fourteen picks,
     * per-pick respins would be two dozen free do-overs and nothing would ever feel risky.
     */
    private int teamRespins;
    private int playerRespins;

    // Eager on purpose. An entry is never useful without its picks, there are always exactly
    // fourteen, and open-in-view is off, so lazy loading would just fail outside the
    // transaction that read the entry.
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "entry_id")
    @OrderBy("pickIndex")
    private List<PickRecord> picks = new ArrayList<>();

    protected EntryRecord() {
    }

    public EntryRecord(String id, String contestId, String owner, Instant createdAt,
                       int teamRespins, int playerRespins) {
        this.id = id;
        this.contestId = contestId;
        this.owner = owner;
        this.createdAt = createdAt;
        this.teamRespins = teamRespins;
        this.playerRespins = playerRespins;
        for (int i = 0; i < Roster.TOTAL_PICKS; i++) {
            picks.add(new PickRecord(i, Roster.positionOf(i), Roster.slotForPick(i)));
        }
    }

    public boolean complete() {
        return picks.stream().allMatch(PickRecord::filled);
    }

    /** The first pick still missing something, which is where the build flow resumes. */
    public PickRecord activePick() {
        return picks.stream().filter(p -> !p.filled()).findFirst().orElse(null);
    }

    public List<PickRecord> picksInPosition(int position) {
        return picks.stream().filter(p -> p.position() == position).toList();
    }

    public String id() { return id; }
    public String contestId() { return contestId; }
    public String owner() { return owner; }
    public Instant createdAt() { return createdAt; }
    public Instant submittedAt() { return submittedAt; }
    public void submittedAt(Instant at) { this.submittedAt = at; }
    public List<PickRecord> picks() { return picks; }
    public int teamRespins() { return teamRespins; }
    public void teamRespins(int n) { this.teamRespins = n; }
    public int playerRespins() { return playerRespins; }
    public void playerRespins(int n) { this.playerRespins = n; }

    public PickRecord pick(int index) {
        return picks.stream().filter(p -> p.pickIndex() == index).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no pick " + index));
    }

    /** One of the fourteen: a player, and which part of their position they cover. */
    @Entity
    @Table(name = "entry_picks")
    public static class PickRecord {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        /** 0 to 11, in build order. */
        private int pickIndex;

        /** 0 to 3: which composite position this pick belongs to. */
        private int position;

        @Enumerated(EnumType.STRING)
        private Slot slot;

        private String team;
        private String playerId;
        private String option;

        protected PickRecord() {
        }

        PickRecord(int pickIndex, int position, Slot slot) {
            this.pickIndex = pickIndex;
            this.position = position;
            this.slot = slot;
        }

        public boolean filled() {
            return team != null && playerId != null && option != null;
        }

        public int pickIndex() { return pickIndex; }
        public int position() { return position; }
        public Slot slot() { return slot; }
        public String team() { return team; }
        public void team(String team) { this.team = team; }
        public String playerId() { return playerId; }
        public void playerId(String playerId) { this.playerId = playerId; }
        public String option() { return option; }
        public void option(String option) { this.option = option; }
    }
}
