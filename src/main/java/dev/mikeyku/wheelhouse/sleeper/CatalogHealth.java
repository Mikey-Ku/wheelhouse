package dev.mikeyku.wheelhouse.sleeper;

import dev.mikeyku.wheelhouse.contest.Contest;
import dev.mikeyku.wheelhouse.contest.ContestService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Ready means the wheel has players on it.
 *
 * <p>The default health check reports UP the moment Postgres answers, which on a cold start is
 * a minute or more before the player catalog has been fetched and parsed. In that window every
 * spin fails and every resumed entry reads "unknown player", and to anyone watching the site
 * looks broken rather than busy. This indicator sits in the readiness group, so
 * {@code /actuator/health/readiness} stays DOWN until there is something to draft from, and
 * the host can route accordingly.
 */
@Component("catalog")
public class CatalogHealth implements HealthIndicator {

    private final PlayerCatalog catalog;
    private final ContestService contests;

    public CatalogHealth(PlayerCatalog catalog, ContestService contests) {
        this.catalog = catalog;
        this.contests = contests;
    }

    @Override
    public Health health() {
        int players = catalog.size();
        Contest contest = contests.known();
        Health.Builder health = players > 0 ? Health.up() : Health.down();
        return health
                .withDetail("players", players)
                .withDetail("contest", contest == null ? "unknown" : contest.id())
                .withDetail("catalogRetries", catalog.failures())
                .build();
    }
}
