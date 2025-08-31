package app.soundcheck.feed.internal.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The preview's and the replay's metrics against the hand-computed cases in
 * {@code contracts/ranking/metric-cases.json}, which the Python lab's {@code evaluate} must also pass.
 */
class RankingMetricsTest {

    static JsonNode cases;
    static double tolerance;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = RankingMetricsTest.class.getResourceAsStream("/ranking/metric-cases.json")) {
            cases = JsonMappers.builder().build().readTree(in);
        }
        tolerance = cases.path("tolerance").asDouble();
        assertThat(tolerance).isEqualTo(1e-9);
    }

    @Test
    void overlapAtK() {
        assertCases("overlapAt", c -> RankingMetrics.overlapAt(c.path("k").asInt(), strings(c, "a"), strings(c, "b")));
    }

    @Test
    void kendallTauB() {
        assertCases("kendallTauB", c -> RankingMetrics.kendallTauB(doubles(c, "x"), doubles(c, "y")));
    }

    @Test
    void auc() {
        assertCases("auc", c -> RankingMetrics.auc(doubles(c, "scores"), booleans(c, "positive")));
    }

    @Test
    void logLoss() {
        assertCases("logLoss", c -> RankingMetrics.logLoss(doubles(c, "probabilities"), booleans(c, "positive")));
    }

    @Test
    void ndcgAtK() {
        assertCases(
                "ndcgAt",
                c -> RankingMetrics.ndcgAt(c.path("k").asInt(), doubles(c, "scores"), booleans(c, "positive")));
    }

    /** The preview's two named checks: the same list twice, and a list against its reverse. */
    @Test
    void identicalListsAgreeCompletelyAndAReversedListDisagreesCompletely() {
        List<String> list = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l");
        double[] ranks = new double[list.size()];
        double[] reversed = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            ranks[i] = i;
            reversed[i] = list.size() - 1 - i;
        }

        assertThat(RankingMetrics.overlapAt(10, list, list)).isEqualTo(1.0);
        assertThat(RankingMetrics.kendallTauB(ranks, ranks)).isEqualTo(1.0);
        assertThat(RankingMetrics.kendallTauB(ranks, reversed)).isEqualTo(-1.0);
    }

    private static void assertCases(String metric, Function<JsonNode, @Nullable Double> compute) {
        JsonNode list = cases.path(metric);
        assertThat(list.size()).as(metric + " cases").isGreaterThanOrEqualTo(3);
        for (JsonNode c : list) {
            Double actual = compute.apply(c);
            String name = metric + ": " + c.path("name").asText("");
            if (c.path("expected").isNull()) {
                assertThat(actual).as(name).isNull();
            } else {
                assertThat(actual).as(name).isNotNull();
                assertThat(actual).as(name).isCloseTo(c.path("expected").asDouble(), within(tolerance));
            }
        }
    }

    private static double[] doubles(JsonNode c, String field) {
        JsonNode array = c.path(field);
        double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).asDouble();
        }
        return values;
    }

    private static boolean[] booleans(JsonNode c, String field) {
        JsonNode array = c.path(field);
        boolean[] values = new boolean[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).asBoolean();
        }
        return values;
    }

    private static List<String> strings(JsonNode c, String field) {
        List<String> values = new ArrayList<>();
        c.path(field).forEach(v -> values.add(v.asText("")));
        return values;
    }
}
