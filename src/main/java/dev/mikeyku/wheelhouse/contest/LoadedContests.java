package dev.mikeyku.wheelhouse.contest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * The archived weeks currently held in memory, stalest first, capped.
 *
 * <p>Every archived week a visitor opens costs about five megabytes across the box scores, the
 * player pool, the projections and the positional fields, and until this class existed none of
 * it was ever released. Seven weeks opened by anonymous requests were enough to take a 512MB
 * instance down. A cap turns that into a fixed cost, and least recently used is the right order
 * to release in, because the same week is visited over and over while an entry is being built.
 *
 * <p>The live week never passes through here. It is loaded by ContestService and GamePoller,
 * not by ArchiveService, so it can never be released.
 */
final class LoadedContests {

    private final int capacity;
    private final Consumer<String> onRelease;
    private final LinkedHashMap<String, Boolean> order = new LinkedHashMap<>(16, 0.75f, true);

    LoadedContests(int capacity, Consumer<String> onRelease) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
        this.onRelease = onRelease;
    }

    /** Marks a week as just used, adding it if new, and releases the stalest weeks over the cap. */
    synchronized void touch(String contestId) {
        order.put(contestId, Boolean.TRUE);
        while (order.size() > capacity) {
            String stalest = order.keySet().iterator().next();
            order.remove(stalest);
            onRelease.accept(stalest);
        }
    }

    synchronized boolean contains(String contestId) {
        return order.containsKey(contestId);
    }

    /** Drops one week and releases whatever it held, for a load that failed part way through. */
    synchronized void evict(String contestId) {
        if (order.remove(contestId) != null) {
            onRelease.accept(contestId);
        }
    }

    synchronized List<String> ids() {
        return new ArrayList<>(order.keySet());
    }

    synchronized int size() {
        return order.size();
    }
}
