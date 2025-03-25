package app.soundcheck.media.internal;

import app.soundcheck.media.MediaUploaded;
import app.soundcheck.media.ObjectStorage;
import app.soundcheck.shared.Ids;
import app.soundcheck.shared.idempotency.IdempotencyKeys;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.RateLimiter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upload sessions. The API never sees the bytes: it creates the asset row, signs
 * URLs for the client to PUT straight to object storage, and on "complete" checks what actually landed
 * there before accepting it. A video then waits for the worker (status UPLOADED → the outbox relays
 * {@link MediaUploaded} to the queue); an image is ready at once.
 */
@Service
public class UploadService {

    static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/quicktime", "video/webm");
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

    /** What a client needs to show or play the asset. */
    public record Playback(
            @Schema(
                            allowableValues = {
                                "AWAITING_UPLOAD",
                                "UPLOADED",
                                "PROCESSING",
                                "READY",
                                "REJECTED",
                                "FAILED",
                                "DELETED"
                            })
                    String status,
            @Schema(nullable = true) String hlsUrl,
            @Schema(nullable = true) String posterUrl,
            @Schema(nullable = true) String thumbUrl,
            @Schema(nullable = true) String blurhash,
            @Schema(nullable = true) Integer durationMs,
            @Schema(nullable = true) Integer width,
            @Schema(nullable = true) Integer height) {}

    private final MediaAssetRepository assets;
    private final ObjectStorage storage;
    private final IdempotencyKeys idempotency;
    private final RateLimiter rateLimiter;
    private final ApplicationEventPublisher events;
    private final MediaProperties properties;
    private final JsonMapper json;
    private final Clock clock;

    public UploadService(
            MediaAssetRepository assets,
            ObjectStorage storage,
            IdempotencyKeys idempotency,
            RateLimiter rateLimiter,
            ApplicationEventPublisher events,
            MediaProperties properties,
            JsonMapper json,
            Clock clock) {
        this.assets = assets;
        this.storage = storage;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.properties = properties;
        this.json = json;
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
        assets.insert(asset, toJson(request.clientMeta() == null ? Map.of() : request.clientMeta()));
        if (idempotencyKey != null) {
            idempotency.complete(ownerId, idempotencyKey, 201, toJson(Map.of("mediaId", id.toString())));
        }
        return ticket(asset);
    }

    /**
     * Accepts the uploaded bytes. Idempotent: once the asset is UPLOADED (or further), calling again
     * returns its status and publishes nothing. The size check uses what object storage reports, not what
     * the client declared; an oversized object is deleted and the asset REJECTED, which must survive the
     * 413 this method answers with (hence {@code noRollbackFor}).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public String complete(UUID ownerId, UUID mediaId, List<CompletedPart> parts) {
        // Row lock: two concurrent completes take turns, and the second sees the first one's result.
        MediaAsset asset = assets.findForUpdate(mediaId)
                .filter(a -> a.ownerId().equals(ownerId) && !a.status().equals("DELETED"))
                .orElseThrow(UploadService::notFound);
        if (asset.uploaded()) {
            return asset.status();
        }
        if (!asset.status().equals("AWAITING_UPLOAD")) {
            throw ApiException.conflict("MEDIA_NOT_UPLOADABLE", "This upload was " + asset.status());
        }
        if (asset.uploadStrategy().equals("MULTIPART")) {
            if (parts.isEmpty()) {
                throw ApiException.badRequest(
                        "PARTS_REQUIRED", "A multipart upload is completed with its parts' ETags");
            }
            List<ObjectStorage.CompletedPart> ordered = parts.stream()
                    .sorted(Comparator.comparingInt(CompletedPart::partNumber))
                    .map(p -> new ObjectStorage.CompletedPart(p.partNumber(), p.etag()))
                    .toList();
            try {
                storage.completeMultipart(asset.originalKey(), asset.multipartUploadId(), ordered);
            } catch (ObjectStorage.InvalidUploadException e) {
                throw ApiException.badRequest("UPLOAD_INCOMPLETE", "The parts do not match what was uploaded");
            }
        }
        ObjectStorage.ObjectHead head = storage.head(asset.originalKey())
                .orElseThrow(() -> ApiException.badRequest("UPLOAD_INCOMPLETE", "Nothing has been uploaded yet"));
        Instant now = Instant.now(clock);
        long limit = (asset.video() ? properties.maxVideoSize() : properties.maxImageSize()).toBytes();
        if (head.contentLength() > limit) {
            storage.delete(asset.originalKey());
            assets.close(asset.id(), "AWAITING_UPLOAD", "REJECTED", "larger than the " + limit + " byte limit", now);
            throw new ApiException(
                    HttpStatus.valueOf(413), "MEDIA_TOO_LARGE", "The uploaded file is larger than allowed");
        }
        assets.markUploaded(asset.id(), "UPLOADED", head.contentLength(), now);
        if (asset.video()) {
            // Recorded in the outbox by this transaction; relayed to the transcode queue after the commit.
            events.publishEvent(new MediaUploaded(asset.id(), ownerId, now));
            return "UPLOADED";
        }
        // Images (avatars) need no transcoding: copy to the served prefix and they are ready.
        String prefix = "media/" + asset.id() + "/v1/";
        String imageKey = prefix + "image." + IMAGE_TYPES.get(asset.contentType());
        storage.copy(asset.originalKey(), imageKey);
        assets.markImageReady(asset.id(), prefix, imageKey, now);
        return "READY";
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
            ObjectStorage.PresignedUrl part =
                    storage.presignUploadPart(asset.originalKey(), asset.multipartUploadId(), n, ttl);
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
                    throw new ApiException(HttpStatus.valueOf(413), "MEDIA_TOO_LARGE", "Videos can be at most 500 MB");
                }
                if (request.durationMs() != null
                        && request.durationMs() > properties.maxVideoDuration().toMillis()) {
                    throw ApiException.badRequest("MEDIA_TOO_LONG", "Clips can be at most 60 seconds");
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
