package dev.mikeyku.wheelhouse.sleeper;

import dev.mikeyku.wheelhouse.contest.ContestService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogHealthTest {

    @Test
    void downUntilTheWheelHasPlayers() {
        PlayerCatalog catalog = new PlayerCatalog(PlayerCatalogRetryTest.flaky(0), 0);
        CatalogHealth health = new CatalogHealth(catalog, new ContestService(null, null));

        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.health().getDetails()).containsEntry("players", 0);

        catalog.refresh();

        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        assertThat(health.health().getDetails()).containsEntry("players", 1);
    }
}
