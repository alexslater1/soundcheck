package app.soundcheck.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ranking config v1 is seeded ACTIVE with exactly the features of the shared schema
 * file, and the database refuses a second ACTIVE config.
 */
class RankingConfigSchemaIT extends IntegrationTest {

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JsonMapper json;

    @Test
    void versionOneIsSeededActiveWithAWeightForEveryFeatureOfTheSchemaAndNoOther() throws Exception {
        JsonNode schema;
        try (InputStream in = getClass().getResourceAsStream("/ranking/feature-schema.json")) {
            schema = json.readTree(in);
        }
        List<String> expected = new ArrayList<>(List.of("bias"));
        schema.path("features")
                .forEach(feature -> expected.add(feature.path("name").asText("")));

        Record seed =
                dsl.fetchOne("select version, feature_schema_version, weights::text, params::text, source, activated_at"
                        + " from feed.ranking_config where status = 'ACTIVE'");

        assertThat(seed.get("version", Integer.class)).isEqualTo(1);
        assertThat(seed.get("source", String.class)).isEqualTo("SEED");
        assertThat(seed.get("activated_at")).isNotNull();
        assertThat(seed.get("feature_schema_version", Integer.class))
                .isEqualTo(schema.path("version").asInt());
        JsonNode weights = json.readTree(seed.get("weights", String.class));
        assertThat(weights.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(weights.path("bias").asDouble()).isEqualTo(-2.0);
        assertThat(weights.path("seen").asDouble()).isEqualTo(-2.5);
        assertThat(weights.path("position_prior").asDouble()).isZero();
        JsonNode params = json.readTree(seed.get("params", String.class));
        assertThat(params.path("halfLifeHours").asInt()).isEqualTo(24);
        assertThat(params.path("sessionSize").asInt()).isEqualTo(200);
        assertThat(params.path("candidateCaps").path("FRESH").asInt()).isEqualTo(100);
    }

    @Test
    void aSecondActiveConfigIsRefusedByTheDatabaseAndADraftIsNot() {
        assertThatThrownBy(() -> insert(2, "ACTIVE")).isInstanceOf(DataIntegrityViolationException.class);

        // Rolled back at the end so other tests still see one config.
        tx.executeWithoutResult(status -> {
            insert(2, "DRAFT");
            insert(3, "ARCHIVED");
            assertThat(dsl.fetchCount(dsl.selectFrom("feed.ranking_config"))).isEqualTo(3);
            status.setRollbackOnly();
        });
        assertThat(dsl.fetchCount(dsl.selectFrom("feed.ranking_config"))).isEqualTo(1);
    }

    private void insert(int version, String status) {
        dsl.execute(
                """
                insert into feed.ranking_config (id, version, status, feature_schema_version, weights, params, source)
                values (?, ?, ?, 1, '{}'::jsonb, '{}'::jsonb, 'MANUAL')
                """,
                UUID.randomUUID(),
                version,
                status);
    }
}
