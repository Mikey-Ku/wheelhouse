package dev.mikeyku.wheelhouse.espn;

import dev.mikeyku.wheelhouse.model.GameSnapshot;
import dev.mikeyku.wheelhouse.model.StatKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parser, against a real box score captured from a live game.
 *
 * <p>Two things here are easy to get wrong and impossible to notice: ESPN gives each category a
 * {@code keys} array and each athlete a bare positional {@code stats} array, so reading by index
 * silently attributes one stat's value to another; and some keys hold two numbers at once.
 */
class BoxscoreParserTest {

    private static GameSnapshot snapshot;

    @BeforeAll
    static void parseTheFixture() throws Exception {
        Path fixture = Path.of("src/test/resources/fixtures/summary-401873279.json");
        JsonNode root = new ObjectMapper().readTree(Files.readString(fixture));
        snapshot = new BoxscoreParser().parse("test", "401873279", root, Instant.EPOCH);
    }

    @Test
    void readsAWholeGameRatherThanAHandfulOfRows() {
        assertThat(snapshot.stats()).hasSizeGreaterThan(200);
        assertThat(snapshot.athleteNames()).isNotEmpty();
        assertThat(snapshot.athleteTeams()).isNotEmpty();
    }

    @Test
    void everyStatBelongsToAnAthleteTheSnapshotCanName() {
        // An orphan stat key is how a scored roster ends up pointing at nobody.
        assertThat(snapshot.stats().keySet())
                .allSatisfy(key -> assertThat(snapshot.athleteNames()).containsKey(key.athleteId()));
    }

    @Test
    void splitsCompoundKeysIntoTheirTwoStats() {
        // "completions/passingAttempts" arrives as one key holding "23/35".
        boolean hasCompletions = snapshot.stats().keySet().stream()
                .anyMatch(k -> k.stat().equals("completions"));
        boolean hasAttempts = snapshot.stats().keySet().stream()
                .anyMatch(k -> k.stat().equals("passingAttempts"));
        assertThat(hasCompletions).as("completions was split out").isTrue();
        assertThat(hasAttempts).as("passingAttempts was split out").isTrue();

        // and the compound key itself must not survive as a stat name
        assertThat(snapshot.stats().keySet()).extracting(StatKey::stat)
                .noneMatch(s -> s.contains("/"));
    }

    @Test
    void negativeYardageIsAValueNotAMalformedCompound() {
        // The separator is detected on the key, never the value, so "-3" rushing yards must
        // parse as minus three rather than as a two-part key.
        assertThat(snapshot.stats().values()).isNotEmpty();
        assertThat(snapshot.stats().entrySet().stream()
                .filter(e -> e.getKey().stat().toLowerCase().contains("yards"))
                .map(java.util.Map.Entry::getValue))
                .as("yardage parsed as numbers")
                .allSatisfy(v -> assertThat(v).isNotNaN());
    }

    @Test
    void reParsingTheSameFixtureProducesTheSameReading() throws Exception {
        // Ingestion diffs consecutive snapshots, so a parser that is not deterministic would
        // manufacture deltas out of nothing every poll.
        Path fixture = Path.of("src/test/resources/fixtures/summary-401873279.json");
        JsonNode root = new ObjectMapper().readTree(Files.readString(fixture));
        GameSnapshot again = new BoxscoreParser().parse("test", "401873279", root, Instant.EPOCH);
        assertThat(again.stats()).isEqualTo(snapshot.stats());
    }
}
