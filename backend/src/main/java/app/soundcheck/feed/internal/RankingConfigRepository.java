package app.soundcheck.feed.internal;

import static app.soundcheck.shared.jooq.feed.Tables.RANKING_CONFIG;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
            UUID id,
            int version,
            String status,
            int featureSchemaVersion,
            String weightsJson,
            String paramsJson,
            String source,
            @Nullable String metricsJson,
            @Nullable String notes,
            @Nullable UUID createdBy,
            Instant createdAt,
            @Nullable Instant activatedAt,
            @Nullable UUID activatedBy) {}

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

    /** The row, locked until the transaction ends: an activation and an edit of the same draft queue up. */
    Optional<Row> findForUpdate(UUID id) {
        return dsl.selectFrom(RANKING_CONFIG)
                .where(RANKING_CONFIG.ID.eq(id))
                .forUpdate()
                .fetchOptional(RankingConfigRepository::toRow);
    }

    /** Every config, newest first. A handful of rows (one per saved version), so no paging and no index. */
    List<Row> list() {
        return dsl.selectFrom(RANKING_CONFIG)
                .orderBy(RANKING_CONFIG.VERSION.desc())
                .fetch(RankingConfigRepository::toRow);
    }

    /**
     * Replaces a DRAFT's weights, parameters and notes.
     *
     * @return false when there is no such config or it is no longer a draft
     */
    boolean updateDraft(UUID id, String weightsJson, String paramsJson, @Nullable String notes) {
        return dsl.update(RANKING_CONFIG)
                        .set(RANKING_CONFIG.WEIGHTS, JSONB.valueOf(weightsJson))
                        .set(RANKING_CONFIG.PARAMS, JSONB.valueOf(paramsJson))
                        .set(RANKING_CONFIG.NOTES, notes)
                        .where(RANKING_CONFIG.ID.eq(id))
                        .and(RANKING_CONFIG.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    /** Adds a DRAFT with the next version number. */
    Row insertDraft(
            UUID id,
            int featureSchemaVersion,
            String weightsJson,
            String paramsJson,
            String source,
            @Nullable String metricsJson,
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
                .set(RANKING_CONFIG.METRICS, metricsJson == null ? null : JSONB.valueOf(metricsJson))
                .set(RANKING_CONFIG.NOTES, notes)
                .set(RANKING_CONFIG.CREATED_BY, createdBy)
                .set(RANKING_CONFIG.CREATED_AT, now.atOffset(ZoneOffset.UTC))
                .execute();
        return new Row(
                id,
                version,
                "DRAFT",
                featureSchemaVersion,
                weightsJson,
                paramsJson,
                source,
                metricsJson,
                notes,
                createdBy,
                now,
                null,
                null);
    }

    /**
     * Makes the config the ACTIVE one: the previous ACTIVE is archived first (the partial unique index
     * allows only one), and the change is announced on {@link #CHANNEL}. PostgreSQL delivers a NOTIFY
     * when the transaction commits, so listeners never hear about a change they cannot yet read.
     */
    void activate(UUID id, @Nullable UUID activatedBy, Instant now) {
        dsl.update(RANKING_CONFIG)
                .set(RANKING_CONFIG.STATUS, "ARCHIVED")
                .where(RANKING_CONFIG.STATUS.eq("ACTIVE"))
                .and(RANKING_CONFIG.ID.ne(id))
                .execute();
        dsl.update(RANKING_CONFIG)
                .set(RANKING_CONFIG.STATUS, "ACTIVE")
                .set(RANKING_CONFIG.ACTIVATED_AT, now.atOffset(ZoneOffset.UTC))
                .set(RANKING_CONFIG.ACTIVATED_BY, activatedBy)
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
                r.getParams().data(),
                r.getSource(),
                r.getMetrics() == null ? null : r.getMetrics().data(),
                r.getNotes(),
                r.getCreatedBy(),
                r.getCreatedAt().toInstant(),
                r.getActivatedAt() == null ? null : r.getActivatedAt().toInstant(),
                r.getActivatedBy());
    }
}
