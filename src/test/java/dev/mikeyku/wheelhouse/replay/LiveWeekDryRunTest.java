package dev.mikeyku.wheelhouse.replay;

import dev.mikeyku.wheelhouse.espn.BoxscoreParser;
import dev.mikeyku.wheelhouse.ingest.IngestService;
import dev.mikeyku.wheelhouse.model.GameSnapshot;
import dev.mikeyku.wheelhouse.model.StatDelta;
import dev.mikeyku.wheelhouse.model.StatKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dry run: a finished game pushed through the live path a poll at a time.
 *
 * <p>Everything the archive path exercises is a single read of a final box score. The live path
 * reads the same game repeatedly while it changes, and that is where the behaviour nobody has
 * ever watched lives. Week 1 is currently scheduled to be both the launch and the first time
 * this code executes, which is the worst possible arrangement, so it executes here instead.
 *
 * <p>Offline: the fixture is a real box score already in the repository.
 */
class LiveWeekDryRunTest {

    private static final String CONTEST = "dryrun";
    private static final String EVENT = "401873279";

    private IngestService ingest;
    private ReplayService replay;
    private GameSnapshot finished;

    @BeforeEach
    void setUp() throws Exception {
        ingest = new IngestService();
        replay = new ReplayService(ingest);
        String json = Files.readString(Path.of("src/test/resources/fixtures/summary-401873279.json"));
        finished = new BoxscoreParser().parse(
                CONTEST, EVENT, new ObjectMapper().readTree(json), Instant.EPOCH);
    }

    @Test
    void aGameInProgressProducesDeltasOnEveryPoll() {
        ReplayService.Replay run = replay.play(finished, 4);

        assertThat(run.ticks()).hasSize(4);
        assertThat(run.totalDeltas())
                .as("a replay that produces no deltas has proved nothing")
                .isGreaterThan(0);
        assertThat(run.ticks()).allSatisfy(tick ->
                assertThat(tick.deltas()).as("stage %d", tick.stage()).isNotEmpty());
    }

    @Test
    void theGameIsInProgressUntilItIsNot() {
        ReplayService.Replay run = replay.play(finished, 4);

        assertThat(run.ticks().subList(0, 3)).extracting(ReplayService.Tick::state)
                .containsOnly("in");
        assertThat(run.ticks().get(3).state()).isEqualTo("post");
    }

    @Test
    void theNumbersGrowThroughTheGameRatherThanArrivingAllAtOnce() {
        ReplayService.Replay run = replay.play(finished, 4);

        // The key count barely moves, because a box score lists everyone who has been on the
        // field whether or not they have done anything. What grows is the values.
        List<Double> magnitude = run.ticks().stream().map(ReplayService.Tick::magnitude).toList();
        assertThat(magnitude).isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(magnitude.get(0)).isLessThan(magnitude.get(magnitude.size() - 1));
    }

    @Test
    void thefinalReadingMatchesTheRealBoxScoreExactly() {
        replay.play(finished, 4);

        GameSnapshot held = ingest.snapshot(CONTEST, EVENT);
        assertThat(held).isNotNull();
        assertThat(held.stats())
                .as("after the last poll the pipeline holds the authoritative numbers")
                .isEqualTo(finished.stats());
    }

    @Test
    void pollingTheSameStateTwiceChangesNothing() {
        // Idempotency is the whole reason ingestion diffs snapshots instead of consuming events.
        // A duplicate poll must be free, or a retry double-counts a touchdown.
        GameSnapshot half = replay.at(finished, 2, 4);
        assertThat(ingest.ingest(half)).isNotEmpty();
        assertThat(ingest.ingest(half)).as("re-ingesting an identical reading").isEmpty();
    }

    @Test
    void aMissedPollIsCaughtUpByTheNextOne() {
        // Self-healing is the property that lets the poller be unreliable. Skipping straight
        // from a quarter to the end must land on the same totals as walking every stage.
        ingest.ingest(replay.at(finished, 1, 4));
        List<StatDelta> jump = ingest.ingest(replay.at(finished, 4, 4));

        assertThat(jump).as("the skipped middle still arrives").isNotEmpty();
        assertThat(ingest.snapshot(CONTEST, EVENT).stats()).isEqualTo(finished.stats());
    }

    @Test
    void aCorrectionMovesTheNumberDownRatherThanBeingIgnored() {
        // Box scores are revised: a catch gets reassigned, a run is ruled a fumble. A pipeline
        // that assumes cumulative totals only grow keeps scoring the number it saw first.
        replay.play(finished, 2);

        Map.Entry<StatKey, Double> biggest = finished.stats().entrySet().stream()
                .filter(e -> e.getKey().stat().toLowerCase().contains("yards"))
                .max(Map.Entry.comparingByValue())
                .orElseThrow();
        double revisedTo = biggest.getValue() - 12;

        List<StatDelta> deltas = ingest.ingest(
                replay.corrected(ingest.snapshot(CONTEST, EVENT), biggest.getKey(), revisedTo));

        assertThat(deltas).as("a correction is a change like any other").isNotEmpty();
        assertThat(deltas).anySatisfy(d -> {
            assertThat(d.key()).isEqualTo(biggest.getKey());
            assertThat(d.change()).isNegative();
        });
        assertThat(ingest.snapshot(CONTEST, EVENT).stats().get(biggest.getKey()))
                .as("the pipeline holds the corrected figure, not the original")
                .isEqualTo(revisedTo);
    }

    @Test
    void oneStageIsJustTheFinalReading() {
        ReplayService.Replay run = replay.play(finished, 1);

        assertThat(run.ticks()).hasSize(1);
        assertThat(run.ticks().get(0).state()).isEqualTo("post");
        assertThat(ingest.snapshot(CONTEST, EVENT).stats()).isEqualTo(finished.stats());
    }

    @Test
    void negativeYardageStaysNegativeWhileTheGameIsInProgress() {
        // Scaling toward zero from the wrong side would show a back who has lost three yards as
        // having gained them, and the sign is the only interesting thing about that number.
        boolean anyNegative = finished.stats().values().stream().anyMatch(v -> v < 0);
        if (!anyNegative) {
            return;
        }
        GameSnapshot mid = replay.at(finished, 2, 4);
        assertThat(mid.stats().values()).allSatisfy(v -> assertThat(v).isNotNaN());
        finished.stats().forEach((key, value) -> {
            Double partial = mid.stats().get(key);
            if (value < 0 && partial != null) {
                assertThat(partial).as("%s", key).isLessThanOrEqualTo(0.0);
            }
        });
    }

    @Test
    void aReplayNeedsAtLeastOneStage() {
        assertThat(java.util.stream.IntStream.of(0, -3)).allSatisfy(stages -> {
            try {
                replay.play(finished, stages);
                org.junit.jupiter.api.Assertions.fail(stages + " stages should be rejected");
            } catch (IllegalArgumentException expected) {
                // the point
            }
        });
    }
}
