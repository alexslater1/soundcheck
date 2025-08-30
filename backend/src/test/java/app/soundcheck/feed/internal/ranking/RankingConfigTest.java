package app.soundcheck.feed.internal.ranking;

import static app.soundcheck.feed.internal.ranking.RankingFixtures.SCHEMA;
import static app.soundcheck.feed.internal.ranking.RankingFixtures.v1Caps;
import static app.soundcheck.feed.internal.ranking.RankingFixtures.v1Params;
import static app.soundcheck.feed.internal.ranking.RankingFixtures.v1Weights;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.feed.internal.ranking.RankingConfig.InvalidRankingConfigException;
import app.soundcheck.feed.internal.ranking.RankingConfig.Params;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Config validation rejects NaN, out-of-range values and names that are not the schema's. */
class RankingConfigTest {

    @Test
    void theSeededConfigIsValidAndItsWeightsAreReadBySchemaIndex() {
        RankingConfig config = RankingFixtures.v1();

        assertThat(config.bias()).isEqualTo(-2.0);
        assertThat(config.weight(SCHEMA.indexOf("engagement_quality"))).isEqualTo(1.8);
        assertThat(config.weight(SCHEMA.indexOf("seen"))).isEqualTo(-2.5);
        assertThat(config.params().cap(CandidateSource.FRESH)).isEqualTo(100);
        assertThat(config.params().sessionSize()).isEqualTo(200);
    }

    @Test
    void weightsMustBeFiniteAndWithinTen() {
        for (double bad : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 10.01, -10.01)) {
            Map<String, Double> weights = v1Weights();
            weights.put("recency", bad);
            assertThat(problems(weights, v1Params()))
                    .as("recency = %s", bad)
                    .containsExactly("weight recency must be a finite number between -10 and 10");
        }
        Map<String, Double> edge = v1Weights();
        edge.put("recency", 10.0);
        edge.put("bias", -10.0);
        assertThat(problems(edge, v1Params())).isEmpty();
    }

    @Test
    void theWeightsMustNameExactlyTheBiasAndTheSchemasFeatures() {
        Map<String, Double> missing = v1Weights();
        missing.remove("seen");
        assertThat(problems(missing, v1Params())).singleElement().asString().contains("missing [seen]");

        Map<String, Double> extra = v1Weights();
        extra.put("mood", 1.0);
        assertThat(problems(extra, v1Params())).singleElement().asString().contains("unknown [mood]");

        assertThat(RankingConfig.problems(2, v1Weights(), v1Params(), SCHEMA))
                .containsExactly("featureSchemaVersion is 2, the schema is version 1");
    }

    @Test
    void everyParameterHasItsRange() {
        assertThat(problems(v1Weights(), params(0, 20, 0.8, 0.1, 200, 10, 50, 3)))
                .containsExactly("halfLifeHours must be above 0 and at most 720");
        assertThat(problems(v1Weights(), params(721, 20, 0.8, 0.1, 200, 10, 50, 3)))
                .containsExactly("halfLifeHours must be above 0 and at most 720");
        assertThat(problems(v1Weights(), params(Double.NaN, 20, 0.8, 0.1, 200, 10, 50, 3)))
                .containsExactly("halfLifeHours must be above 0 and at most 720");
        assertThat(problems(v1Weights(), params(24, 0, 0.8, 0.1, 200, 10, 50, 3)))
                .containsExactly("priorStrength must be a finite number above 0");
        assertThat(problems(v1Weights(), params(24, 20, -0.01, 0.1, 200, 10, 50, 3)))
                .containsExactly("diversityLambda must be between 0 and 1");
        assertThat(problems(v1Weights(), params(24, 20, 1.01, 0.1, 200, 10, 50, 3)))
                .containsExactly("diversityLambda must be between 0 and 1");
        assertThat(problems(v1Weights(), params(24, 20, 0.8, 0.31, 200, 10, 50, 3)))
                .containsExactly("explorationRate must be between 0 and 0.3");
        assertThat(problems(v1Weights(), params(24, 20, 0.8, Double.NaN, 200, 10, 50, 3)))
                .containsExactly("explorationRate must be between 0 and 0.3");
        assertThat(problems(v1Weights(), params(24, 20, 0.8, 0.1, 0, 10, 50, 3)))
                .containsExactly("sessionSize must be between 1 and 500");
        assertThat(problems(v1Weights(), params(24, 20, 0.8, 0.1, 200, 51, 50, 3)))
                .containsExactly("pageSize must be between 1 and 50");
        assertThat(problems(v1Weights(), params(24, 20, 0.8, 0.1, 200, 10, 0, 3)))
                .containsExactly("localRadiusKm must be above 0 and at most 1000");
        assertThat(problems(v1Weights(), params(24, 20, 0.8, 0.1, 200, 10, 50, 31)))
                .containsExactly("seenWindowDays must be between 0 and 30");
        // The edges themselves are allowed.
        assertThat(problems(v1Weights(), params(720, 20, 0, 0.3, 500, 50, 1000, 0)))
                .isEmpty();
        assertThat(problems(v1Weights(), params(0.5, 20, 1, 0, 1, 1, 50, 30))).isEmpty();
    }

    @Test
    void theAuthorCapIsBetweenOneAndFifty() {
        assertThat(problems(v1Weights(), withAuthorCap(0)))
                .containsExactly("authorCapPerSession must be between 1 and 50");
        assertThat(problems(v1Weights(), withAuthorCap(51)))
                .containsExactly("authorCapPerSession must be between 1 and 50");
        assertThat(problems(v1Weights(), withAuthorCap(1))).isEmpty();
        assertThat(problems(v1Weights(), withAuthorCap(50))).isEmpty();
    }

    @Test
    void candidateCapsMustNameEverySourceAndStayWithinAThousand() {
        Map<String, Integer> tooBig = v1Caps();
        tooBig.put("TASTE", 1001);
        assertThat(problems(v1Weights(), withCaps(tooBig)))
                .containsExactly("candidateCaps.TASTE must be between 0 and 1000");

        Map<String, Integer> negative = v1Caps();
        negative.put("FRESH", -1);
        assertThat(problems(v1Weights(), withCaps(negative)))
                .containsExactly("candidateCaps.FRESH must be between 0 and 1000");

        Map<String, Integer> incomplete = v1Caps();
        incomplete.remove("LOCAL");
        incomplete.put("VIRAL", 10);
        assertThat(problems(v1Weights(), withCaps(incomplete)))
                .singleElement()
                .asString()
                .contains("must name exactly");

        Map<String, Integer> zero = v1Caps();
        zero.put("TRENDING", 0);
        assertThat(problems(v1Weights(), withCaps(zero))).isEmpty();
    }

    @Test
    void anInvalidConfigCannotBeBuiltAndTheErrorListsEveryProblem() {
        Map<String, Double> weights = v1Weights();
        weights.put("recency", Double.NaN);

        assertThatThrownBy(() -> RankingConfig.of(
                        UUID.randomUUID(), 2, 1, weights, params(0, 20, 2, 0.1, 200, 10, 50, 3), SCHEMA))
                .isInstanceOfSatisfying(InvalidRankingConfigException.class, e -> assertThat(e.problems())
                        .containsExactlyInAnyOrder(
                                "weight recency must be a finite number between -10 and 10",
                                "halfLifeHours must be above 0 and at most 720",
                                "diversityLambda must be between 0 and 1"));
    }

    /** A parameter left out of the stored JSON would be read as 0, a valid value for some: it must be named. */
    @Test
    void everyParameterMustBeNamedInTheStoredDocument() {
        assertThat(Params.missingFrom(Params.NAMES)).isEmpty();
        assertThat(Params.missingFrom(List.of(
                        "halfLifeHours", "priorStrength", "sessionSize", "pageSize", "candidateCaps", "localRadiusKm")))
                .containsExactly("diversityLambda", "explorationRate", "seenWindowDays", "authorCapPerSession");
        // The record's components and the list of names are the same ten.
        assertThat(java.util.Arrays.stream(Params.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName))
                .containsExactlyElementsOf(Params.NAMES);
        // A document without candidateCaps is a validation problem, not a NullPointerException.
        assertThat(problems(v1Weights(), new Params(24, 20, 0.8, 0.1, 200, 10, null, 50, 3, 5)))
                .singleElement()
                .asString()
                .contains("candidateCaps must name exactly");
    }

    private static List<String> problems(Map<String, Double> weights, Params params) {
        return RankingConfig.problems(1, weights, params, SCHEMA);
    }

    private static Params params(
            double halfLife,
            double priorStrength,
            double lambda,
            double exploration,
            int sessionSize,
            int pageSize,
            double radiusKm,
            int seenDays) {
        return new Params(
                halfLife, priorStrength, lambda, exploration, sessionSize, pageSize, v1Caps(), radiusKm, seenDays, 5);
    }

    private static Params withAuthorCap(int cap) {
        return new Params(24, 20, 0.8, 0.1, 200, 10, v1Caps(), 50, 3, cap);
    }

    private static Params withCaps(Map<String, Integer> caps) {
        return new Params(24, 20, 0.8, 0.1, 200, 10, caps, 50, 3, 5);
    }
}
