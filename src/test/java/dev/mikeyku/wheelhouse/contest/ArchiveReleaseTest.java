package dev.mikeyku.wheelhouse.contest;

import dev.mikeyku.wheelhouse.ingest.IngestService;
import dev.mikeyku.wheelhouse.model.GameSnapshot;
import dev.mikeyku.wheelhouse.model.StatKey;
import dev.mikeyku.wheelhouse.projection.PositionalField;
import dev.mikeyku.wheelhouse.projection.ProjectionService;
import dev.mikeyku.wheelhouse.sleeper.PlayerCatalog;
import dev.mikeyku.wheelhouse.wheel.WheelPool;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Releasing a week has to reach every service that holds a piece of it, and must leave the
 * week beside it alone. The services are real; only the network clients are absent, because
 * nothing here goes near them.
 */
class ArchiveReleaseTest {

    private static final String KEPT = "a2024-2-1";
    private static final String RELEASED = "a2024-2-2";
    private static final String MAHOMES = "14876";

    @Test
    void releaseReachesEveryHolderAndSparesTheNeighbour() {
        IngestService ingest = new IngestService();
        PlayerCatalog catalog = new PlayerCatalog(null);
        ArchiveRoster roster = new ArchiveRoster(ingest, catalog);
        ProjectionService projections = new ProjectionService(null);
        PositionalField field = new PositionalField(new WheelPool(catalog, roster, 400), projections);
        ArchiveService archive = new ArchiveService(null, null, ingest, null, null,
                projections, roster, catalog, field, 5, 8);

        for (String contest : new String[] {KEPT, RELEASED}) {
            ingest.ingest(snapshot(contest, "401"));
            roster.players(contest);
        }
        assertThat(ingest.hasContest(RELEASED)).isTrue();
        assertThat(catalog.byId("espn:" + RELEASED + ":" + MAHOMES)).isNotNull();

        archive.release(RELEASED);

        assertThat(ingest.hasContest(RELEASED)).isFalse();
        assertThat(catalog.byId("espn:" + RELEASED + ":" + MAHOMES)).isNull();
        assertThat(roster.players(RELEASED)).isEmpty();

        assertThat(ingest.hasContest(KEPT)).isTrue();
        assertThat(catalog.byId("espn:" + KEPT + ":" + MAHOMES)).isNotNull();
        assertThat(roster.players(KEPT)).hasSize(1);
    }

    @Test
    void anEmptyPoolIsNeverRemembered() {
        IngestService ingest = new IngestService();
        ArchiveRoster roster = new ArchiveRoster(ingest, new PlayerCatalog(null));

        // A look before the box scores land must not pin "nobody" for the week.
        assertThat(roster.players(KEPT)).isEmpty();
        ingest.ingest(snapshot(KEPT, "401"));
        assertThat(roster.players(KEPT)).hasSize(1);
    }

    /** One quarterback who threw enough to be inferred as one. */
    private static GameSnapshot snapshot(String contestId, String eventId) {
        return new GameSnapshot(contestId, eventId, "KC @ BAL", "post", "Final", Instant.now(),
                Map.of(new StatKey(MAHOMES, "passing", "passingAttempts"), 30.0,
                       new StatKey(MAHOMES, "passing", "passingYards"), 250.0),
                Map.of(MAHOMES, "Patrick Mahomes"),
                Map.of(MAHOMES, "KC"));
    }
}
