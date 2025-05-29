package app.soundcheck.feed.internal.ranking;

import static app.soundcheck.feed.internal.ranking.RankingFixtures.SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The scorer, and its parity with the Python lab: every case of
 * {@code contracts/ranking/parity-cases.json}, written by Python, must score the same here.
 */
class ScorerTest {

    @Test
    void everyParityCaseWrittenByPythonScoresTheSameInJava() throws Exception {
        JsonNode document;
        try (InputStream in = getClass().getResourceAsStream("/ranking/parity-cases.json")) {
            document = JsonMappers.builder().build().readTree(in);
        }
        assertThat(document.path("schemaVersion").asInt()).isEqualTo(SCHEMA.version());
        double tolerance = document.path("tolerance").asDouble();
        assertThat(tolerance).isEqualTo(1e-6);
        assertThat(document.path("cases").size()).isGreaterThanOrEqualTo(20);

        for (JsonNode parityCase : document.path("cases")) {
            Map<String, Double> weights = new HashMap<>();
            parityCase
                    .path("weights")
                    .properties()
                    .forEach(entry ->
                            weights.put(entry.getKey(), entry.getValue().asDouble()));
            double[] features = new double[SCHEMA.size()];
            for (int i = 0; i < features.length; i++) {
                features[i] = parityCase.path("features").get(i).asDouble();
            }

            Scorer.Scored scored = Scorer.score(RankingFixtures.config(weights), FeatureVector.of(features));

            assertThat(scored.score())
                    .as(parityCase.path("name").asText(""))
                    .isCloseTo(parityCase.path("expectedScore").asDouble(), within(tolerance));
            // A score near 0 or 1 hardly moves with the logit, so the logit is compared too.
            assertThat(scored.logit())
                    .as(parityCase.path("name").asText("") + " (logit)")
                    .isCloseTo(parityCase.path("expectedLogit").asDouble(), within(tolerance));
        }
    }

    @Test
    void aPostNothingIsKnownAboutScoresTheSigmoidOfTheBias() {
        Scorer.Scored scored =
                Scorer.score(RankingFixtures.v1(), FeatureVector.builder(SCHEMA).build());

        assertThat(scored.logit()).isEqualTo(-2.0);
        assertThat(scored.score()).isCloseTo(0.11920292202211755, within(1e-15));
        assertThat(scored.contributions()).containsOnly(0.0);
    }

    @Test
    void theScoreSplitsIntoOneContributionPerFeature() {
        FeatureVector features = FeatureVector.builder(SCHEMA)
                .set("follows_author", 1)
                .set("recency", 0.5)
                .set("seen", 1)
                .build();

        Scorer.Scored scored = Scorer.score(RankingFixtures.v1(), features);

        assertThat(scored.contributions()[SCHEMA.indexOf("follows_author")]).isEqualTo(1.6);
        assertThat(scored.contributions()[SCHEMA.indexOf("recency")]).isEqualTo(0.75);
        assertThat(scored.contributions()[SCHEMA.indexOf("seen")]).isEqualTo(-2.5);
        assertThat(scored.logit()).isCloseTo(-2.0 + 1.6 + 0.75 - 2.5, within(1e-12));
        assertThat(scored.score()).isCloseTo(1 / (1 + Math.exp(2.15)), within(1e-12));
    }

    @Test
    void theSigmoidSurvivesVeryLargeInputs() {
        assertThat(Scorer.sigmoid(0)).isEqualTo(0.5);
        assertThat(Scorer.sigmoid(1000)).isEqualTo(1.0);
        assertThat(Scorer.sigmoid(-1000)).isZero();
        assertThat(Scorer.sigmoid(-3) + Scorer.sigmoid(3)).isCloseTo(1.0, within(1e-15));
    }
}
