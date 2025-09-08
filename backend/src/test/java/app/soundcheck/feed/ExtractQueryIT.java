package app.soundcheck.feed;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.shared.Ids;
import app.soundcheck.support.IntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code contracts/ranking/extract.sql}, the lab's training table, run here exactly as the
 * Python {@code extract} runs it (the file is on the test classpath as {@code ranking/extract.sql}). Rows are
 * written by hand, as in {@link ServedOutcomeIT}: each case shows one label rule, and that the serving
 * config's version and feature schema version come from {@code feed.ranking_config}.
 */
class ExtractQueryIT extends IntegrationTest {

    /** Ranking config v1 (V015): version 1, feature schema 1. */
    static final UUID V1 = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired
    DSLContext dsl;

    private UUID otherConfig;

    record Served(UUID session, UUID user, UUID post, Instant at, UUID config) {}

    @AfterEach
    void removeOtherConfig() {
        if (otherConfig != null) {
            dsl.execute("delete from feed.ranking_config where id = ?", otherConfig);
        }
    }

    @Test
    void aLikeIsPositiveAndCarriesTheServingConfig() {
        Served s = serve(V1, false);
        event(s, "LIKE", null, 5);
        Record row = extracted(s);
        assertThat(row.get("positive", Boolean.class)).isTrue();
        assertThat(row.get("negative", Boolean.class)).isFalse();
        assertThat(row.get("config_id", String.class)).isEqualTo(V1.toString());
        assertThat(row.get("config_version", Integer.class)).isEqualTo(1);
        assertThat(row.get("feature_schema_version", Integer.class)).isEqualTo(1);
        assertThat(row.get("session_id", String.class)).isEqualTo(s.session().toString());
        assertThat(row.get("position", Integer.class)).isZero();
    }

    @Test
    void watchingThreeQuartersIsPositiveAndAnImpressionAloneIsNot() {
        Served watched = serve(V1, false);
        event(watched, "WATCH_PROGRESS", 0.75f, 10);
        assertThat(extracted(watched).get("positive", Boolean.class)).isTrue();

        Served glanced = serve(V1, false);
        event(glanced, "IMPRESSION", null, 1);
        event(glanced, "WATCH_PROGRESS", 0.5f, 4);
        Record row = extracted(glanced);
        assertThat(row.get("positive", Boolean.class)).isFalse();
        assertThat(row.get("negative", Boolean.class)).isFalse();
    }

    @Test
    void aHideWinsOverALike() {
        Served s = serve(V1, false);
        event(s, "LIKE", null, 5);
        event(s, "HIDE", null, 8);
        Record row = extracted(s);
        assertThat(row.get("positive", Boolean.class)).isFalse();
        assertThat(row.get("negative", Boolean.class)).isTrue();
    }

    @Test
    void theFeaturesAndExplorationFlagComeThroughAsLogged() {
        Served s = serve(V1, true);
        Record row = extracted(s);
        assertThat(row.get("exploration", Boolean.class)).isTrue();
        Float[] features = row.get("features", Float[].class);
        assertThat(features).hasSize(12);
        assertThat(features[0]).isEqualTo(1.0f);
        assertThat(features[7]).isEqualTo(0.5f);
        assertThat(features[11]).as("position_prior is logged as 0").isEqualTo(0.0f);
    }

    @Test
    void theFeatureSchemaVersionIsTheServingConfigs() {
        otherConfig = Ids.newId();
        dsl.execute(
                "insert into feed.ranking_config (id, version, status, feature_schema_version, weights, params,"
                        + " source) select ?, (select max(version) + 1 from feed.ranking_config), 'DRAFT', 2,"
                        + " weights, params, 'LAB' from feed.ranking_config where id = ?",
                otherConfig,
                V1);
        Served s = serve(otherConfig, false);
        Record row = extracted(s);
        assertThat(row.get("feature_schema_version", Integer.class)).isEqualTo(2);
        assertThat(row.get("config_version", Integer.class)).isGreaterThan(1);
    }

    /** The lab's query, as the lab runs it, narrowed to one served item. */
    private Record extracted(Served s) {
        return dsl.fetchSingle(
                "select * from (" + extractSql() + ") x where session_id = ? and position = 0",
                s.session().toString());
    }

    static String extractSql() {
        try (InputStream in = ExtractQueryIT.class.getClassLoader().getResourceAsStream("ranking/extract.sql")) {
            assertThat(in).as("ranking/extract.sql on the test classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Served serve(UUID config, boolean exploration) {
        Served s =
                new Served(Ids.newId(), Ids.newId(), Ids.newId(), Instant.now().minus(1, ChronoUnit.MINUTES), config);
        dsl.execute(
                "insert into feed.served_item (session_id, position, served_at, user_id, post_id, config_id, score,"
                        + " features, exploration) values (?, 0, ?::timestamptz, ?, ?, ?, 0.5,"
                        + " '{1,0,0,0,0,0,0,0.5,0,0,0,0}'::real[], ?)",
                s.session(),
                s.at().toString(),
                s.user(),
                s.post(),
                s.config(),
                exploration);
        return s;
    }

    private void event(Served s, String type, Float value, int secondsAfterServe) {
        dsl.execute(
                "insert into signals.interaction_event (occurred_at, user_id, post_id, feed_session_id, surface,"
                        + " position, event_type, value) values (?::timestamptz, ?, ?, ?, 'FOR_YOU', 0, ?, ?)",
                s.at().plusSeconds(secondsAfterServe).toString(),
                s.user(),
                s.post(),
                s.session(),
                type,
                value);
    }
}
