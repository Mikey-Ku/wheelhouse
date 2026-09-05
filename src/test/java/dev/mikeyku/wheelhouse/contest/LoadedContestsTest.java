package dev.mikeyku.wheelhouse.contest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoadedContestsTest {

    @Test
    void releasesTheStalestWeekOnceOverCapacity() {
        List<String> released = new ArrayList<>();
        LoadedContests loaded = new LoadedContests(3, released::add);

        loaded.touch("a2024-2-1");
        loaded.touch("a2024-2-2");
        loaded.touch("a2024-2-3");
        assertThat(released).isEmpty();

        loaded.touch("a2024-2-4");
        assertThat(released).containsExactly("a2024-2-1");
        assertThat(loaded.ids()).containsExactly("a2024-2-2", "a2024-2-3", "a2024-2-4");
    }

    @Test
    void revisitingAWeekKeepsItAlive() {
        List<String> released = new ArrayList<>();
        LoadedContests loaded = new LoadedContests(3, released::add);

        loaded.touch("a2024-2-1");
        loaded.touch("a2024-2-2");
        loaded.touch("a2024-2-3");
        loaded.touch("a2024-2-1");   // someone is still building an entry here
        loaded.touch("a2024-2-4");

        assertThat(released).containsExactly("a2024-2-2");
        assertThat(loaded.contains("a2024-2-1")).isTrue();
    }

    @Test
    void evictReleasesExplicitlyAndOnlyOnce() {
        List<String> released = new ArrayList<>();
        LoadedContests loaded = new LoadedContests(3, released::add);

        loaded.touch("a2024-2-1");
        loaded.evict("a2024-2-1");
        loaded.evict("a2024-2-1");
        loaded.evict("a2024-2-9");

        assertThat(released).containsExactly("a2024-2-1");
        assertThat(loaded.size()).isZero();
    }
}
