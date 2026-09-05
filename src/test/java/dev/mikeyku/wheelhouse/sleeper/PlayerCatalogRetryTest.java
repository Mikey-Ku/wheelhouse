package dev.mikeyku.wheelhouse.sleeper;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cold container has no cache, so the first catalog load is a live 14MB call from a
 * datacenter address. If that one call fails, the wheel must come back in seconds, not at the
 * next twelve-hour interval.
 */
class PlayerCatalogRetryTest {

    private static final String ONE_PLAYER = """
            {"1": {"active": true, "full_name": "Patrick Mahomes", "position": "QB",
                   "team": "KC", "espn_id": "3139477", "search_rank": 1}}
            """;

    /** A Sleeper that fails a set number of times and then answers with one player. */
    static SleeperClient flaky(int failuresBeforeSuccess) {
        return new SleeperClient("/tmp/wheelhouse-test-cache", 20) {
            private int calls;

            @Override
            public JsonNode players() throws IOException {
                if (calls++ < failuresBeforeSuccess) {
                    throw new IOException("Sleeper returned 503");
                }
                return new ObjectMapper().readTree(ONE_PLAYER);
            }
        };
    }

    @Test
    void aFailureRetriesInSecondsNotHours() {
        PlayerCatalog catalog = new PlayerCatalog(flaky(2), Duration.ofHours(12).toMillis());
        Instant before = Instant.now();

        catalog.refresh();
        assertThat(catalog.size()).isZero();
        assertThat(catalog.failures()).isEqualTo(1);
        assertThat(catalog.nextAttempt()).isBetween(before.plusSeconds(14), Instant.now().plusSeconds(16));

        catalog.refresh();
        assertThat(catalog.failures()).isEqualTo(2);
        assertThat(catalog.nextAttempt()).isBetween(before.plusSeconds(29), Instant.now().plusSeconds(31));

        catalog.refresh();
        assertThat(catalog.size()).isEqualTo(1);
        assertThat(catalog.failures()).isZero();
        assertThat(catalog.nextAttempt()).isAfter(Instant.now().plus(Duration.ofHours(11)));
    }

    @Test
    void tickWaitsOutTheBackoffInsteadOfHammering() {
        PlayerCatalog catalog = new PlayerCatalog(flaky(1), 0);

        catalog.tick();
        assertThat(catalog.failures()).isEqualTo(1);

        catalog.tick();
        assertThat(catalog.failures()).as("a second tick inside the backoff must not call Sleeper").isEqualTo(1);
        assertThat(catalog.size()).isZero();
    }

    @Test
    void backoffDoublesToACeiling() {
        assertThat(PlayerCatalog.backoff(1)).isEqualTo(Duration.ofSeconds(15));
        assertThat(PlayerCatalog.backoff(2)).isEqualTo(Duration.ofSeconds(30));
        assertThat(PlayerCatalog.backoff(6)).isEqualTo(Duration.ofSeconds(480));
        assertThat(PlayerCatalog.backoff(7)).isEqualTo(Duration.ofMinutes(10));
        assertThat(PlayerCatalog.backoff(40)).isEqualTo(Duration.ofMinutes(10));
    }
}
