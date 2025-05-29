package app.soundcheck.feed.internal;

import static app.soundcheck.shared.jooq.feed.Tables.RANKING_CONFIG;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** {@code feed.ranking_config}: every version of the ranking, one of them ACTIVE. */
@Repository
public class RankingConfigRepository {

    /** The channel a change of the active config is announced on. */
    public static final String CHANNEL = "ranking_config_changed";

    /** A stored config: the JSON documents as they are, not yet checked against the schema. */
    public record Row(
            UUID id, int version, String status, int featureSchemaVersion, String weightsJson, String paramsJson) {}

    private final DSLContext dsl;

    RankingConfigRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Expected index: ranking_config_one_active (at most one row). */
    Optional<Row> findActive() {
        return dsl.selectFrom(RANKING_CONFIG)
                .where(RANKING_CONFIG.STATUS.eq("ACTIVE"))
                .fetchOptional(RankingConfigRepository::toRow);
    }

    Optional<Row> find(UUID id) {
        return dsl.selectFrom(RANKING_CONFIG)
                .where(RANKING_CONFIG.ID.eq(id))
                .fetchOptional(RankingConfigRepository::toRow);
    }

    /** Adds a DRAFT with the next version number. */
    Row insertDraft(
            UUID id,
            int featureSchemaVersion,
            String weightsJson,
            String paramsJson,
            String source,
            @Nullable String notes,
            @Nullable UUID createdBy,
            Instant now) {
        // The unique constraint on version settles two drafts created at once: one of them fails.
        int version = dsl.select(
                        DSL.coalesce(DSL.max(RANKING_CONFIG.VERSION), 0).plus(1))
                .from(RANKING_CONFIG)
                .fetchSingle()
                .value1();
        dsl.insertInto(RANKING_CONFIG)
                .set(RANKING_CONFIG.ID, id)
                .set(RANKING_CONFIG.VERSION, version)
                .set(RANKING_CONFIG.STATUS, "DRAFT")
                .set(RANKING_CONFIG.FEATURE_SCHEMA_VERSION, featureSchemaVersion)
                .set(RANKING_CONFIG.WEIGHTS, JSONB.valueOf(weightsJson))
                .set(RANKING_CONFIG.PARAMS, JSONB.valueOf(paramsJson))
                .set(RANKING_CONFIG.SOURCE, source)
                .set(RANKING_CONFIG.NOTES, notes)
                .set(RANKING_CONFIG.CREATED_BY, createdBy)
                .set(RANKING_CONFIG.CREATED_AT, now.atOffset(ZoneOffset.UTC))
                .execute();
        return new Row(id, version, "DRAFT", featureSchemaVersion, weightsJson, paramsJson);
    }

    /**
     * Makes the config the ACTIVE one: the previous ACTIVE is archived first (the partial unique index
     * allows only one), and the change is announced on {@link #CHANNEL}. PostgreSQL delivers a NOTIFY
     * when the transaction commits, so listeners never hear about a change they cannot yet read.
     */
    void activate(UUID id, Instant now) {
        dsl.update(RANKING_CONFIG)
                .set(RANKING_CONFIG.STATUS, "ARCHIVED")
                .where(RANKING_CONFIG.STATUS.eq("ACTIVE"))
                .and(RANKING_CONFIG.ID.ne(id))
                .execute();
        dsl.update(RANKING_CONFIG)
                .set(RANKING_CONFIG.STATUS, "ACTIVE")
                .set(RANKING_CONFIG.ACTIVATED_AT, now.atOffset(ZoneOffset.UTC))
                .where(RANKING_CONFIG.ID.eq(id))
                .execute();
        dsl.fetch("select pg_notify(?, ?)", CHANNEL, id.toString());
    }

    private static Row toRow(app.soundcheck.shared.jooq.feed.tables.records.RankingConfigRecord r) {
        return new Row(
                r.getId(),
                r.getVersion(),
                r.getStatus(),
                r.getFeatureSchemaVersion(),
                r.getWeights().data(),
                r.getParams().data());
    }
}
