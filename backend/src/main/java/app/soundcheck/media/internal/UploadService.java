package app.soundcheck.media.internal;

import app.soundcheck.media.MediaUploaded;
import app.soundcheck.media.ObjectStorage;
import app.soundcheck.media.Playback;
import app.soundcheck.shared.Ids;
import app.soundcheck.shared.idempotency.IdempotencyKeys;
import app.soundcheck.shared.observability.Traceparent;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.RateLimiter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Upload sessions. The API never sees the bytes: it creates the asset row, signs
 * URLs for the client to PUT straight to object storage, and on "complete" checks what actually landed
 * there before accepting it. A video then waits for the worker (status UPLOADED → the outbox relays
 * {@link MediaUploaded} to the queue); an image is ready at once.
 */
@Service
public class UploadService {

    static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/quicktime", "video/webm");
    static final int MAX_CLIENT_META_CHARS = 2_000;
    /** Served paths are versioned and never rewritten, so they may be cached for good. */
    static final String IMMUTABLE = "public, max-age=31536000, immutable";

    static final Map<String, String> IMAGE_TYPES =
            Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    /** {@code POST /media/uploads} body. */
    public record CreateUpload(
            String kind,
            String contentType,
            long bytes,
            @Nullable Integer durationMs,
            @Nullable Integer width,
            @Nullable Integer height,
            @Nullable Map<String, Object> clientMeta) {}

    public record PartUrl(int partNumber, String url) {}

    /** What the client needs to upload: one PUT (SINGLE) or a URL per part (MULTIPART). */
    public record UploadTicket(
            UUID mediaId,
            @Schema(allowableValues = {"SINGLE", "MULTIPART"}) String strategy,
            @Schema(nullable = true) String putUrl,
            @Schema(nullable = true) Map<String, String> headers,
            @Schema(nullable = true) Integer partSize,
            @Schema(nullable = true) List<PartUrl> parts,
            Instant expiresAt) {}

    public record CompletedPart(int partNumber, String etag) {}

    private final MediaAssetRepository assets;
    private final ObjectStorage storage;
    private final IdempotencyKeys idempotency;
    private final RateLimiter rateLimiter;
    private final ApplicationEventPublisher events;
    private final MediaProperties properties;
    private final JsonMapper json;
    private final Traceparent traceparent;
    private final TransactionTemplate tx;
    private final Clock clock;

    public UploadService(
            MediaAssetRepository assets,
            ObjectStorage storage,
            IdempotencyKeys idempotency,
            RateLimiter rateLimiter,
            ApplicationEventPublisher events,
            MediaProperties properties,
            JsonMapper json,
            Traceparent traceparent,
            TransactionTemplate tx,
            Clock clock) {
        this.assets = assets;
        this.traceparent = traceparent;
        this.storage = storage;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.properties = properties;
        this.json = json;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Opens an upload. With an {@code Idempotency-Key}, a retry of the same request returns the same
     * asset (with freshly signed URLs; URLs are never stored) instead of creating a second one.
     */
    @Transactional
    public UploadTicket create(UUID ownerId, CreateUpload request, @Nullable String idempotencyKey) {
        validate(request);
        if (idempotencyKey != null) {
            Optional<String> remembered =
                    idempotency.begin(ownerId, idempotencyKey, "POST /media/uploads " + toJson(request));
            if (remembered.isPresent()) {
                JsonNode stored;
                try {
                    stored = json.readTree(remembered.get());
                } catch (JsonProcessingException e) {
                    throw new UncheckedIOException(e);
                }
                MediaAsset existing =
                        owned(ownerId, UUID.fromString(stored.path("mediaId").asText("")));
                if (!existing.status().equals("AWAITING_UPLOAD")) {
                    throw ApiException.conflict(
                            "UPLOAD_ALREADY_COMPLETED", "Upload " + existing.id() + " was already completed");
                }
                return ticket(existing);
            }
        }
        // Free-form, client-supplied and stored: keep it small.
        String clientMeta = toJson(request.clientMeta() == null ? Map.of() : request.clientMeta());
        if (clientMeta.length() > MAX_CLIENT_META_CHARS) {
            throw ApiException.badRequest("CLIENT_META_TOO_LARGE", "clientMeta is too large");
        }
        rateLimiter.enforce("uploads:" + ownerId, properties.uploadsPerHour(), Duration.ofHours(1));

        UUID id = Ids.newId();
        // The key is derived from ids only; a client-supplied file name never reaches storage.
        String key = "uploads/" + ownerId + "/" + id + "/original";
        boolean multipart = request.bytes() > properties.singleUploadMax().toBytes();
        String uploadId = multipart ? storage.createMultipart(key, request.contentType()) : null;
        MediaAsset asset = new MediaAsset(
                id,
                ownerId,
                request.kind(),
                "AWAITING_UPLOAD",
                request.contentType(),
                request.bytes(),
                key,
                multipart ? "MULTIPART" : "SINGLE",
                uploadId,
                multipart ? (int) properties.partSize().toBytes() : null,
                request.durationMs(),
                request.width(),
                request.height(),
                null,
                null,
                null,
                null,
                0,
                null,
                Instant.now(clock));
        assets.insert(asset, clientMeta);
        if (idempotencyKey != null) {
            idempotency.complete(ownerId, idempotencyKey, 201, toJson(Map.of("mediaId", id.toString())));
        }
        return ticket(asset);
    }

    /**
     * Accepts the uploaded bytes. Idempotent: once the asset is UPLOADED (or further), calling again
     * returns its status and publishes nothing. The size check uses what object storage reports, not what
     * the client declared; an oversized object is deleted and the asset REJECTED, and that must survive the
     * 413 this method answers with, so the locked step returns the error and it is thrown after the commit.
     *
     * <p>An image (an avatar) is checked and stripped of its metadata <em>before</em> the asset's row is
     * locked: that work downloads, decodes and re-encodes up to 10 MB, and doing it inside
     * the transaction held the row lock and a database connection for all of it.
     */
    public String complete(UUID ownerId, UUID mediaId, List<CompletedPart> parts) {
        MediaAsset seen = owned(ownerId, mediaId);
        PreparedImage image =
                !seen.video() && seen.status().equals("AWAITING_UPLOAD") ? prepareImage(seen, parts) : null;
        Outcome outcome;
        try {
            outcome = Objects.requireNonNull(tx.execute(status -> completeLocked(ownerId, mediaId, parts, image)));
        } catch (RuntimeException e) {
            // The asset went away or changed state while its image was prepared: nothing will serve the copy.
            discardServedCopy(image);
            throw e;
        }
        if (outcome.error() != null) {
            throw outcome.error();
        }
        return outcome.status();
    }

    /** What the locked step decided: a status to answer with, or an error to throw once its writes are committed. */
    private record Outcome(String status, @Nullable ApiException error) {
        static Outcome answer(String status) {
            return new Outcome(status, null);
        }

        static Outcome refuse(String status, ApiException error) {
            return new Outcome(status, error);
        }
    }

    /**
     * An image checked and cleaned before the lock: its size in storage, and either the key its served
     * copy was stored at or why it was refused ({@code tooLarge} or {@code problem}).
     */
    private record PreparedImage(
            long bytes, boolean tooLarge, String prefix, String imageKey, @Nullable String problem) {
        boolean stored() {
            return !tooLarge && problem == null;
        }
    }

    private PreparedImage prepareImage(MediaAsset asset, List<CompletedPart> parts) {
        if (asset.uploadStrategy().equals("MULTIPART")) {
            assembleMultipart(asset, parts); // only with a singleUploadMax below maxImageSize
        }
        ObjectStorage.ObjectHead head = storage.head(asset.originalKey())
                .orElseThrow(() -> ApiException.badRequest("UPLOAD_INCOMPLETE", "Nothing has been uploaded yet"));
        String prefix = "media/" + asset.id() + "/v1/";
        String imageKey = prefix + "image." + IMAGE_TYPES.get(asset.contentType());
        if (head.contentLength() > properties.maxImageSize().toBytes()) {
            return new PreparedImage(head.contentLength(), true, prefix, imageKey, null);
        }
        // Images (avatars) need no transcoding, but nothing is served unchecked or as uploaded: the bytes
        // must be an image of the declared type, and what is served is a copy without
        // the file's metadata (a photo's EXIF holds the camera, the time and often the GPS position).
        return new PreparedImage(head.contentLength(), false, prefix, imageKey, storeServedImage(asset, imageKey));
    }

    private void discardServedCopy(@Nullable PreparedImage image) {
        if (image != null && image.stored()) {
            try {
                storage.delete(image.imageKey());
            } catch (RuntimeException e) {
                // An orphaned copy under media/ is harmless; the original error matters more.
            }
        }
    }

    /** The part of {@link #complete} that runs in one transaction, with the asset's row locked. */
    private Outcome completeLocked(
            UUID ownerId, UUID mediaId, List<CompletedPart> parts, @Nullable PreparedImage image) {
        // Row lock: two concurrent completes take turns, and the second sees the first one's result.
        MediaAsset asset = assets.findForUpdate(mediaId)
                .filter(a -> a.ownerId().equals(ownerId) && !a.status().equals("DELETED"))
                .orElseThrow(UploadService::notFound);
        if (asset.uploaded()) {
            // Another complete got here first; for an image it stored the same cleaned bytes at the same key.
            return Outcome.answer(asset.status());
        }
        if (!asset.status().equals("AWAITING_UPLOAD")) {
            throw ApiException.conflict("MEDIA_NOT_UPLOADABLE", "This upload was " + asset.status());
        }
        Instant now = Instant.now(clock);
        if (!asset.video()) {
            return completeImage(asset, Objects.requireNonNull(image, "an image is prepared before the lock"), now);
        }
        if (asset.uploadStrategy().equals("MULTIPART")) {
            assembleMultipart(asset, parts);
        }
        ObjectStorage.ObjectHead head = storage.head(asset.originalKey())
                .orElseThrow(() -> ApiException.badRequest("UPLOAD_INCOMPLETE", "Nothing has been uploaded yet"));
        long limit = properties.maxVideoSize().toBytes();
        if (head.contentLength() > limit) {
            return reject(asset, "AWAITING_UPLOAD", limit, now);
        }
        assets.markUploaded(asset.id(), "UPLOADED", head.contentLength(), now);
        // Recorded in the outbox by this transaction; relayed to the transcode queue after the commit.
        events.publishEvent(new MediaUploaded(
                asset.id(), ownerId, now, traceparent.current().orElse(null)));
        return Outcome.answer("UPLOADED");
    }

    private Outcome completeImage(MediaAsset asset, PreparedImage image, Instant now) {
        if (image.tooLarge()) {
            return reject(asset, "AWAITING_UPLOAD", properties.maxImageSize().toBytes(), now);
        }
        assets.markUploaded(asset.id(), "UPLOADED", image.bytes(), now);
        if (image.problem() != null) {
            storage.delete(asset.originalKey());
            assets.close(asset.id(), "UPLOADED", "REJECTED", image.problem(), now);
            return Outcome.refuse(
                    "REJECTED", ApiException.badRequest("IMAGE_INVALID", "The uploaded file is not a valid image"));
        }
        assets.markImageReady(asset.id(), image.prefix(), image.imageKey(), now);
        return Outcome.answer("READY");
    }

    /** Deletes an oversized upload and marks it REJECTED; the 413 is thrown after the commit. */
    private Outcome reject(MediaAsset asset, String fromStatus, long limit, Instant now) {
        storage.delete(asset.originalKey());
        assets.close(asset.id(), fromStatus, "REJECTED", "larger than the " + limit + " byte limit", now);
        return Outcome.refuse(
                "REJECTED",
                new ApiException(
                        HttpStatus.valueOf(413), "MEDIA_TOO_LARGE", "The uploaded file is larger than allowed"));
    }

    /**
     * Completes a multipart upload in storage. Wrong parts, or an earlier complete that assembled the
     * object and then failed to commit (storage no longer knows the upload id): only a finished multipart
     * upload can have put an object at this key, so if one is there it is the second case; carry on.
     */
    private void assembleMultipart(MediaAsset asset, List<CompletedPart> parts) {
        if (parts.isEmpty()) {
            throw ApiException.badRequest("PARTS_REQUIRED", "A multipart upload is completed with its parts' ETags");
        }
        List<ObjectStorage.CompletedPart> ordered = parts.stream()
                .sorted(Comparator.comparingInt(CompletedPart::partNumber))
                .map(p -> new ObjectStorage.CompletedPart(p.partNumber(), p.etag()))
                .toList();
        try {
            storage.completeMultipart(asset.originalKey(), asset.multipartUploadId(), ordered);
        } catch (ObjectStorage.InvalidUploadException e) {
            if (storage.head(asset.originalKey()).isEmpty()) {
                throw ApiException.badRequest("UPLOAD_INCOMPLETE", "The parts do not match what was uploaded");
            }
        }
    }

    /**
     * Downloads the (at most 10 MB) image, checks its header, and stores the scrubbed copy
     * ({@link ImageScrub}) at {@code imageKey}. Returns why the image was refused, or null.
     */
    private @Nullable String storeServedImage(MediaAsset asset, String imageKey) {
        Path original = null;
        Path served = null;
        try {
            original = Files.createTempFile("soundcheck-image-", ".bin");
            Files.delete(original); // the storage client writes the file itself and refuses an existing one
            storage.getToFile(asset.originalKey(), original);
            String problem = ImageCheck.problem(original, asset.contentType());
            if (problem != null) {
                return problem;
            }
            served = Files.createTempFile("soundcheck-image-", ".out");
            try {
                ImageScrub.scrub(original, asset.contentType(), served);
            } catch (IOException | RuntimeException e) {
                // A header that reads well over pixels the JDK cannot decode (a CMYK JPEG, say).
                return "not a decodable image";
            }
            storage.putFile(imageKey, served, asset.contentType(), IMMUTABLE);
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(original);
            deleteQuietly(served);
        }
    }

    private static void deleteQuietly(@Nullable Path temp) {
        if (temp != null) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                // A leftover temp file is not worth failing the request for.
            }
        }
    }

    /** Abandons an upload that has not been completed. */
    @Transactional
    public void abort(UUID ownerId, UUID mediaId) {
        MediaAsset asset = assets.findForUpdate(mediaId)
                .filter(a -> a.ownerId().equals(ownerId) && !a.status().equals("DELETED"))
                .orElseThrow(UploadService::notFound);
        if (!asset.status().equals("AWAITING_UPLOAD")) {
            throw ApiException.conflict(
                    "UPLOAD_NOT_ABORTABLE", "Only an upload that is still in progress can be aborted");
        }
        if (asset.multipartUploadId() != null) {
            storage.abortMultipart(asset.originalKey(), asset.multipartUploadId());
        }
        storage.delete(asset.originalKey());
        assets.close(asset.id(), "AWAITING_UPLOAD", "DELETED", null, Instant.now(clock));
    }

    /** The owner sees their asset in any state; everyone else only once it is READY. */
    @Transactional(readOnly = true)
    public Playback playback(UUID viewerId, UUID mediaId) {
        MediaAsset asset = assets.find(mediaId)
                .filter(a -> !a.status().equals("DELETED"))
                .filter(a -> a.ownerId().equals(viewerId) || a.status().equals("READY"))
                .orElseThrow(UploadService::notFound);
        return toPlayback(asset);
    }

    Playback toPlayback(MediaAsset asset) {
        boolean ready = asset.status().equals("READY");
        String base = properties.publicBaseUrl() + "/";
        return new Playback(
                asset.status(),
                ready && asset.video() ? base + asset.outputPrefix() + "master.m3u8" : null,
                ready && asset.posterKey() != null ? base + asset.posterKey() : null,
                ready && asset.thumbKey() != null ? base + asset.thumbKey() : null,
                ready ? asset.blurhash() : null,
                asset.durationMs(),
                asset.width(),
                asset.height());
    }

    private UploadTicket ticket(MediaAsset asset) {
        Duration ttl = properties.urlTtl();
        if (asset.uploadStrategy().equals("SINGLE")) {
            ObjectStorage.PresignedUrl put =
                    storage.presignPut(asset.originalKey(), asset.contentType(), asset.declaredBytes(), ttl);
            return new UploadTicket(
                    asset.id(),
                    "SINGLE",
                    put.url().toString(),
                    Map.of("Content-Type", asset.contentType()),
                    null,
                    null,
                    put.expiresAt());
        }
        int partSize = asset.partSize();
        int count = (int) ((asset.declaredBytes() + partSize - 1) / partSize);
        List<PartUrl> parts = new ArrayList<>(count);
        Instant expiresAt = Instant.now(clock).plus(ttl);
        for (int n = 1; n <= count; n++) {
            // Every part is signed for exactly its length: full parts, then whatever is left.
            long length = Math.min(partSize, asset.declaredBytes() - (long) (n - 1) * partSize);
            ObjectStorage.PresignedUrl part =
                    storage.presignUploadPart(asset.originalKey(), asset.multipartUploadId(), n, length, ttl);
            parts.add(new PartUrl(n, part.url().toString()));
            expiresAt = part.expiresAt();
        }
        return new UploadTicket(asset.id(), "MULTIPART", null, null, partSize, parts, expiresAt);
    }

    private void validate(CreateUpload request) {
        switch (request.kind()) {
            case "VIDEO" -> {
                if (!VIDEO_TYPES.contains(request.contentType())) {
                    throw ApiException.badRequest("CONTENT_TYPE_UNSUPPORTED", "Videos must be mp4, quicktime or webm");
                }
                if (request.bytes() > properties.maxVideoSize().toBytes()) {
                    throw new ApiException(
                            HttpStatus.valueOf(413),
                            "MEDIA_TOO_LARGE",
                            "Videos can be at most " + MediaLimits.videoSize(properties.maxVideoSize()));
                }
                if (request.durationMs() != null
                        && request.durationMs() > properties.maxVideoDuration().toMillis()) {
                    throw ApiException.badRequest(
                            "MEDIA_TOO_LONG",
                            "Clips can be at most " + MediaLimits.clipLength(properties.maxVideoDuration()));
                }
            }
            case "IMAGE" -> {
                if (!IMAGE_TYPES.containsKey(request.contentType())) {
                    throw ApiException.badRequest("CONTENT_TYPE_UNSUPPORTED", "Images must be jpeg, png or webp");
                }
                if (request.bytes() > properties.maxImageSize().toBytes()) {
                    throw new ApiException(HttpStatus.valueOf(413), "MEDIA_TOO_LARGE", "Images can be at most 10 MB");
                }
            }
            default -> throw ApiException.badRequest("KIND_INVALID", "kind must be VIDEO or IMAGE");
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private MediaAsset owned(UUID ownerId, UUID mediaId) {
        return assets.find(mediaId)
                .filter(a -> a.ownerId().equals(ownerId) && !a.status().equals("DELETED"))
                .orElseThrow(UploadService::notFound);
    }

    /** Someone else's upload is indistinguishable from one that does not exist. */
    private static ApiException notFound() {
        return ApiException.notFound("MEDIA_NOT_FOUND", "No such media");
    }
}
