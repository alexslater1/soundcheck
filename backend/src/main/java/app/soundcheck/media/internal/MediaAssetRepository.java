package app.soundcheck.media.internal;

import static app.soundcheck.shared.jooq.media.Tables.MEDIA_ASSET;
import static app.soundcheck.shared.jooq.media.Tables.TRANSCODE_JOB;

import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
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

    /** {@code contentHash}: the client's hash of the file, or null when it sent none. */
    public void insert(MediaAsset asset, String clientMetaJson, @Nullable String contentHash) {
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
                .set(MEDIA_ASSET.CONTENT_HASH, contentHash)
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

    /** The assets with these ids (missing ids are simply absent). Expected index: the primary key. */
    public List<MediaAsset> findAll(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(MEDIA_ASSET).where(MEDIA_ASSET.ID.in(ids)).fetch(MediaAssetRepository::toAsset);
    }

    /**
     * The owner's assets with this content hash that still exist (not DELETED, FAILED or REJECTED), newest
     * first: the candidates for the duplicate guard. Expected index: media_asset_owner_hash
     * (V146), whose predicate this query repeats.
     */
    public List<UUID> idsWithHash(UUID ownerId, String contentHash, int limit) {
        return dsl.select(MEDIA_ASSET.ID)
                .from(MEDIA_ASSET)
                .where(MEDIA_ASSET.OWNER_ID.eq(ownerId))
                .and(MEDIA_ASSET.CONTENT_HASH.eq(contentHash))
                .and(MEDIA_ASSET.STATUS.notIn("DELETED", "FAILED", "REJECTED"))
                .orderBy(MEDIA_ASSET.CREATED_AT.desc())
                .limit(limit)
                .fetch(MEDIA_ASSET.ID);
    }

    /**
     * Any status but DELETED → DELETED, with {@code deleted_at} (a deleted post's media). The
     * files stay until {@link #purgeable} picks the asset up a week later. Expected index: the primary key.
     *
     * @return how many assets changed
     */
    public int release(Collection<UUID> ids, Instant now) {
        if (ids.isEmpty()) {
            return 0;
        }
        return dsl.update(MEDIA_ASSET)
                .set(MEDIA_ASSET.STATUS, "DELETED")
                .set(MEDIA_ASSET.DELETED_AT, at(now))
                .set(MEDIA_ASSET.UPDATED_AT, at(now))
                .where(MEDIA_ASSET.ID.in(ids))
                .and(MEDIA_ASSET.STATUS.ne("DELETED"))
                .execute();
    }

    /** A DELETED asset whose files are still in storage: where they are. */
    public record Purgeable(UUID id, UUID ownerId) {}

    /**
     * DELETED assets released before {@code cutoff} whose files have not been removed yet, oldest first.
     * Expected index: media_asset_deleted (V147), partial on exactly this predicate.
     */
    public List<Purgeable> purgeable(Instant cutoff, int limit) {
        return dsl.select(MEDIA_ASSET.ID, MEDIA_ASSET.OWNER_ID)
                .from(MEDIA_ASSET)
                .where(MEDIA_ASSET.STATUS.eq("DELETED"))
                .and(MEDIA_ASSET.OBJECTS_PURGED_AT.isNull())
                .and(MEDIA_ASSET.DELETED_AT.lt(at(cutoff)))
                .orderBy(MEDIA_ASSET.DELETED_AT, MEDIA_ASSET.ID)
                .limit(limit)
                .fetch(r -> new Purgeable(r.get(MEDIA_ASSET.ID), r.get(MEDIA_ASSET.OWNER_ID)));
    }

    /** Records that the asset's files are gone; the row stays (content's clips still reference it). */
    public void markObjectsPurged(UUID id, Instant now) {
        dsl.update(MEDIA_ASSET)
                .set(MEDIA_ASSET.OBJECTS_PURGED_AT, at(now))
                .where(MEDIA_ASSET.ID.eq(id))
                .execute();
    }

    /** Every asset id of an owner, whatever its status. Expected index: media_asset_owner. */
    public List<UUID> idsOwnedBy(UUID ownerId) {
        return dsl.select(MEDIA_ASSET.ID)
                .from(MEDIA_ASSET)
                .where(MEDIA_ASSET.OWNER_ID.eq(ownerId))
                .fetch(MEDIA_ASSET.ID);
    }

    /** Deletes the owner's asset rows (their transcode_job rows go with them). */
    public int deleteOwnedBy(UUID ownerId) {
        return dsl.deleteFrom(MEDIA_ASSET)
                .where(MEDIA_ASSET.OWNER_ID.eq(ownerId))
                .execute();
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

    /**
     * Marks an image READY with where it is served from: the photo is its own poster, and its small copy
     * the thumbnail. A decoded image also gets its measured size, which replaces the size the client
     * declared (a phone may report a portrait photo's stored, sideways size), and its BlurHash.
     */
    public void markImageReady(
            UUID id,
            String outputPrefix,
            String imageKey,
            String thumbKey,
            ImageScrub.@Nullable Served served,
            Instant now) {
        var update = dsl.update(MEDIA_ASSET)
                .set(MEDIA_ASSET.STATUS, "READY")
                .set(MEDIA_ASSET.OUTPUT_PREFIX, outputPrefix)
                .set(MEDIA_ASSET.POSTER_KEY, imageKey)
                .set(MEDIA_ASSET.THUMB_KEY, thumbKey)
                .set(MEDIA_ASSET.READY_AT, at(now))
                .set(MEDIA_ASSET.UPDATED_AT, at(now));
        if (served != null) {
            update = update.set(MEDIA_ASSET.WIDTH, served.width())
                    .set(MEDIA_ASSET.HEIGHT, served.height())
                    .set(MEDIA_ASSET.BLURHASH, served.blurhash());
        }
        update.where(MEDIA_ASSET.ID.eq(id)).execute();
    }

    /**
     * Terminal state from the upload side: REJECTED (with why) or DELETED (an aborted upload, dated so
     * that MediaObjectPurge takes it too).
     */
    public int close(UUID id, String fromStatus, String status, @Nullable String reason, Instant now) {
        return dsl.update(MEDIA_ASSET)
                .set(MEDIA_ASSET.STATUS, status)
                .set(MEDIA_ASSET.FAILURE_REASON, reason)
                .set(MEDIA_ASSET.DELETED_AT, status.equals("DELETED") ? at(now) : null)
                .set(MEDIA_ASSET.UPDATED_AT, at(now))
                .where(MEDIA_ASSET.ID.eq(id))
                .and(MEDIA_ASSET.STATUS.eq(fromStatus))
                .execute();
    }

    /**
     * An operator's Retry: back to UPLOADED with a fresh set of attempts, the failure
     * and any lease cleared. The caller holds the row lock and has checked the asset may be retried.
     */
    public void requeue(UUID id, Instant now) {
        dsl.update(MEDIA_ASSET)
                .set(MEDIA_ASSET.STATUS, "UPLOADED")
                .set(MEDIA_ASSET.ATTEMPTS, 0)
                .setNull(MEDIA_ASSET.FAILURE_REASON)
                .setNull(MEDIA_ASSET.CLAIMED_BY)
                .setNull(MEDIA_ASSET.CLAIMED_AT)
                .set(MEDIA_ASSET.UPDATED_AT, at(now))
                .where(MEDIA_ASSET.ID.eq(id))
                .execute();
    }

    /** An operator's Fail of a stranded asset: FAILED with {@code reason}, lease cleared. */
    public void failByOperator(UUID id, String reason, Instant now) {
        dsl.update(MEDIA_ASSET)
                .set(MEDIA_ASSET.STATUS, "FAILED")
                .set(MEDIA_ASSET.FAILURE_REASON, reason)
                .setNull(MEDIA_ASSET.CLAIMED_BY)
                .setNull(MEDIA_ASSET.CLAIMED_AT)
                .set(MEDIA_ASSET.UPDATED_AT, at(now))
                .where(MEDIA_ASSET.ID.eq(id))
                .execute();
    }

    /**
     * Closes an attempt still marked RUNNING whose worker is gone, so the job history shows how it ended.
     * Expected index: transcode_job_media (media_id, attempt).
     */
    public int closeRunningJobs(UUID mediaId, String error, Instant now) {
        return dsl.update(TRANSCODE_JOB)
                .set(TRANSCODE_JOB.STATUS, "FAILED")
                .set(TRANSCODE_JOB.ERROR, error)
                .set(TRANSCODE_JOB.FINISHED_AT, at(now))
                .where(TRANSCODE_JOB.MEDIA_ID.eq(mediaId))
                .and(TRANSCODE_JOB.STATUS.eq("RUNNING"))
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
                hasAudio(r.get(MEDIA_ASSET.PROBE)),
                r.get(MEDIA_ASSET.ATTEMPTS),
                r.get(MEDIA_ASSET.FAILURE_REASON),
                r.get(MEDIA_ASSET.CREATED_AT).toInstant());
    }

    private static final JsonMapper PROBE_JSON = JsonMappers.builder().build();

    /**
     * Whether the original had an audio track, read from the stored probe. The transcoder adds silence to
     * a clip without audio, so the HLS output cannot tell the app: only the probe can.
     */
    static @Nullable Boolean hasAudio(@Nullable JSONB probe) {
        if (probe == null) {
            return null;
        }
        JsonNode flag;
        try {
            flag = PROBE_JSON.readTree(probe.data()).path("hasAudio");
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        return flag.isBoolean() ? flag.asBoolean() : null;
    }

    static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
