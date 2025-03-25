package app.soundcheck.media.internal;

import static app.soundcheck.shared.jooq.media.Tables.MEDIA_ASSET;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** jOOQ access to {@code media.media_asset}. */
@Repository
public class MediaAssetRepository {

    private final DSLContext dsl;

    public MediaAssetRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(MediaAsset asset, String clientMetaJson) {
        dsl.insertInto(MEDIA_ASSET)
                .set(MEDIA_ASSET.ID, asset.id())
                .set(MEDIA_ASSET.OWNER_ID, asset.ownerId())
                .set(MEDIA_ASSET.KIND, asset.kind())
                .set(MEDIA_ASSET.STATUS, asset.status())
                .set(MEDIA_ASSET.CONTENT_TYPE, asset.contentType())
                .set(MEDIA_ASSET.DECLARED_BYTES, asset.declaredBytes())
                .set(MEDIA_ASSET.ORIGINAL_KEY, asset.originalKey())
                .set(MEDIA_ASSET.UPLOAD_STRATEGY, asset.uploadStrategy())
                .set(MEDIA_ASSET.MULTIPART_UPLOAD_ID, asset.multipartUploadId())
                .set(MEDIA_ASSET.PART_SIZE, asset.partSize())
                .set(MEDIA_ASSET.CLIENT_META, JSONB.valueOf(clientMetaJson))
                .set(MEDIA_ASSET.DURATION_MS, asset.durationMs())
                .set(MEDIA_ASSET.WIDTH, asset.width())
                .set(MEDIA_ASSET.HEIGHT, asset.height())
                .set(MEDIA_ASSET.CREATED_AT, at(asset.createdAt()))
                .set(MEDIA_ASSET.UPDATED_AT, at(asset.createdAt()))
                .execute();
    }

    public Optional<MediaAsset> find(UUID id) {
        return dsl.selectFrom(MEDIA_ASSET).where(MEDIA_ASSET.ID.eq(id)).fetchOptional(MediaAssetRepository::toAsset);
    }

    /** Reads the row and locks it until the transaction ends ({@code SELECT … FOR UPDATE}). */
    public Optional<MediaAsset> findForUpdate(UUID id) {
        return dsl.selectFrom(MEDIA_ASSET)
                .where(MEDIA_ASSET.ID.eq(id))
                .forUpdate()
                .fetchOptional(MediaAssetRepository::toAsset);
    }

    /** The owner's assets, newest first. Expected index: media_asset_owner (owner_id, created_at desc). */
    public List<MediaAsset> byOwner(UUID ownerId, int limit) {
        return dsl.selectFrom(MEDIA_ASSET)
                .where(MEDIA_ASSET.OWNER_ID.eq(ownerId))
                .and(MEDIA_ASSET.STATUS.ne("DELETED"))
                .orderBy(MEDIA_ASSET.CREATED_AT.desc(), MEDIA_ASSET.ID.desc())
                .limit(limit)
                .fetch(MediaAssetRepository::toAsset);
    }

    /**
     * AWAITING_UPLOAD → {@code status}, conditionally: returns 0 when another request got there first,
     * which makes a concurrent double "complete" harmless.
     */
    public int markUploaded(UUID id, String status, long actualBytes, Instant now) {
        return dsl.update(MEDIA_ASSET)
                .set(MEDIA_ASSET.STATUS, status)
                .set(MEDIA_ASSET.ACTUAL_BYTES, actualBytes)
                .set(MEDIA_ASSET.UPLOADED_AT, at(now))
                .set(MEDIA_ASSET.UPDATED_AT, at(now))
                .where(MEDIA_ASSET.ID.eq(id))
                .and(MEDIA_ASSET.STATUS.eq("AWAITING_UPLOAD"))
                .execute();
    }

    /** Marks an image READY with where it is served from. */
    public void markImageReady(UUID id, String outputPrefix, String imageKey, Instant now) {
        dsl.update(MEDIA_ASSET)
                .set(MEDIA_ASSET.STATUS, "READY")
                .set(MEDIA_ASSET.OUTPUT_PREFIX, outputPrefix)
                .set(MEDIA_ASSET.POSTER_KEY, imageKey)
                .set(MEDIA_ASSET.THUMB_KEY, imageKey)
                .set(MEDIA_ASSET.READY_AT, at(now))
                .set(MEDIA_ASSET.UPDATED_AT, at(now))
                .where(MEDIA_ASSET.ID.eq(id))
                .execute();
    }

    /** Terminal state from the upload side: REJECTED (with why) or DELETED. */
    public int close(UUID id, String fromStatus, String status, @Nullable String reason, Instant now) {
        return dsl.update(MEDIA_ASSET)
                .set(MEDIA_ASSET.STATUS, status)
                .set(MEDIA_ASSET.FAILURE_REASON, reason)
                .set(MEDIA_ASSET.UPDATED_AT, at(now))
                .where(MEDIA_ASSET.ID.eq(id))
                .and(MEDIA_ASSET.STATUS.eq(fromStatus))
                .execute();
    }

    static MediaAsset toAsset(Record r) {
        return new MediaAsset(
                r.get(MEDIA_ASSET.ID),
                r.get(MEDIA_ASSET.OWNER_ID),
                r.get(MEDIA_ASSET.KIND),
                r.get(MEDIA_ASSET.STATUS),
                r.get(MEDIA_ASSET.CONTENT_TYPE),
                r.get(MEDIA_ASSET.DECLARED_BYTES),
                r.get(MEDIA_ASSET.ORIGINAL_KEY),
                r.get(MEDIA_ASSET.UPLOAD_STRATEGY),
                r.get(MEDIA_ASSET.MULTIPART_UPLOAD_ID),
                r.get(MEDIA_ASSET.PART_SIZE),
                r.get(MEDIA_ASSET.DURATION_MS),
                r.get(MEDIA_ASSET.WIDTH),
                r.get(MEDIA_ASSET.HEIGHT),
                r.get(MEDIA_ASSET.OUTPUT_PREFIX),
                r.get(MEDIA_ASSET.POSTER_KEY),
                r.get(MEDIA_ASSET.THUMB_KEY),
                r.get(MEDIA_ASSET.BLURHASH),
                r.get(MEDIA_ASSET.ATTEMPTS),
                r.get(MEDIA_ASSET.FAILURE_REASON),
                r.get(MEDIA_ASSET.CREATED_AT).toInstant());
    }

    static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
