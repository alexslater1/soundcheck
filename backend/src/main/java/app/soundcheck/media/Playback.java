package app.soundcheck.media;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a client needs to show or play an asset. URLs are present once READY.
 * A photo has no {@code hlsUrl}: its {@code posterUrl} is the photo itself and its
 * {@code thumbUrl} a small copy.
 */
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
        @Schema(nullable = true) Integer height,
        /** False when the original clip has no sound; null when unknown (not processed yet, images). */
        @Schema(nullable = true) Boolean hasAudio,
        /** What the asset is: a clip plays, a photo is shown. Null for a post slot still waiting for its file. */
        @Schema(
                        nullable = true,
                        allowableValues = {"VIDEO", "IMAGE"})
                String kind) {}
