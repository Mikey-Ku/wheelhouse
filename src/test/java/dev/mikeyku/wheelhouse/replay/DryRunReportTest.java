package dev.mikeyku.wheelhouse.replay;

import dev.mikeyku.wheelhouse.espn.BoxscoreParser;
import dev.mikeyku.wheelhouse.ingest.IngestService;
import dev.mikeyku.wheelhouse.model.GameSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prints a replay rather than only asserting one.
 *
 * <p>A dry run nobody can watch is half a dry run. This is the same machinery as the assertions
 * next door, run at eight stages and printed, so the behaviour can be read rather than inferred
 * from a green tick. Run it with {@code ./mvnw test -Dtest=DryRunReportTest}.
 */
class DryRunReportTest {

    @Test
    void printAReplay() throws Exception {
        IngestService ingest = new IngestService();
        ReplayService replay = new ReplayService(ingest);

        String json = Files.readString(Path.of("src/test/resources/fixtures/summary-401873279.json"));
        GameSnapshot finished = new BoxscoreParser().parse(
                "dryrun", "401873279", new ObjectMapper().readTree(json), Instant.EPOCH);

        System.out.printf("%n  %s%n  %d stats across %d players%n%n",
                finished.name(), finished.stats().size(), finished.athleteNames().size());
        System.out.printf("  %-6s %-6s %10s %10s%n", "stage", "state", "magnitude", "deltas");
        System.out.println("  " + "-".repeat(36));

        ReplayService.Replay run = replay.play(finished, 8);
        for (ReplayService.Tick tick : run.ticks()) {
            System.out.printf("  %-6d %-6s %10.0f %10d%n",
                    tick.stage(), tick.state(), tick.magnitude(), tick.deltas().size());
        }
        System.out.printf("%n  total deltas: %d%n", run.totalDeltas());
        System.out.printf("  final reading matches the real box score: %s%n%n",
                ingest.snapshot("dryrun", "401873279").stats().equals(finished.stats()));

        assertThat(run.totalDeltas()).isGreaterThan(0);
    }
}
