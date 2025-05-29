package app.soundcheck.feed.internal.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

/** The feature schema file is the single source of the feature order. */
class FeatureSchemaTest {

    static final JsonMapper JSON = JsonMappers.builder().build();

    @Test
    void theSharedSchemaFileIsOnTheClasspathWithTheTwelveFeaturesInOrder() {
        FeatureSchema schema = FeatureSchema.load();

        assertThat(schema.version()).isEqualTo(1);
        assertThat(schema.names())
                .containsExactly(
                        "follows_author",
                        "follows_artist",
                        "artist_affinity",
                        "author_affinity",
                        "genre_similarity",
                        "gig_relation",
                        "proximity",
                        "recency",
                        "engagement_quality",
                        "social_proof",
                        "seen",
                        "position_prior");
        assertThat(schema.indexOf("recency")).isEqualTo(7);
        assertThatThrownBy(() -> schema.indexOf("mood")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFileWhoseIndexesDoNotCountUpInOrderOrThatRepeatsANameIsRefused() {
        assertThatThrownBy(
                        () -> FeatureSchema.parse(
                                JSON.readTree(
                                        """
                        {"version": 2, "features": [{"index": 0, "name": "a"}, {"index": 2, "name": "b"}]}
                        """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count up from 0");
        assertThatThrownBy(
                        () -> FeatureSchema.parse(
                                JSON.readTree(
                                        """
                        {"version": 2, "features": [{"index": 0, "name": "a"}, {"index": 1, "name": "a"}]}
                        """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void aFeatureVectorIsBuiltByNameCutToTheUnitIntervalAndRefusesNotANumber() {
        FeatureSchema schema = FeatureSchema.load();

        FeatureVector vector = FeatureVector.builder(schema)
                .set("recency", 0.25)
                .set("proximity", 1.7)
                .set("seen", -3)
                .build();

        assertThat(vector.size()).isEqualTo(12);
        assertThat(vector.get(schema.indexOf("recency"))).isEqualTo(0.25);
        assertThat(vector.get(schema.indexOf("proximity"))).isEqualTo(1.0);
        assertThat(vector.get(schema.indexOf("seen"))).isZero();
        assertThat(vector.get(schema.indexOf("follows_author"))).isZero();
        assertThat(vector.toFloats()).hasSize(12).contains(0.25f, 1.0f);
        assertThatThrownBy(() -> FeatureVector.builder(schema).set("recency", Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
