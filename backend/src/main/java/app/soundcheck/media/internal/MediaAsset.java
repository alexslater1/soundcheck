package app.soundcheck.media.internal;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One row of {@code media.media_asset}, as the upload API and the worker see it. */
public record MediaAsset(
        UUID id,
        UUID ownerId,
        String kind,
        String status,
        String contentType,
        long declaredBytes,
        String originalKey,
        String uploadStrategy,
        @Nullable String multipartUploadId,
        @Nullable Integer partSize,
        @Nullable Integer durationMs,
        @Nullable Integer width,
        @Nullable Integer height,
        @Nullable String outputPrefix,
        @Nullable String posterKey,
        @Nullable String thumbKey,
        @Nullable String blurhash,
        /** From the stored probe: false = the original had no audio track; null = not probed yet. */
        @Nullable Boolean hasAudio,
        int attempts,
        @Nullable String failureReason,
        Instant createdAt) {

    public boolean video() {
        return kind.equals("VIDEO");
    }

    /**
     * True once the bytes have been accepted: completing again must change nothing and answers with the
     * current status. FAILED is such a state: the upload
     * itself succeeded, the processing did not.
     */
    public boolean uploaded() {
        return status.equals("UPLOADED")
                || status.equals("PROCESSING")
                || status.equals("READY")
                || status.equals("FAILED");
    }
}
