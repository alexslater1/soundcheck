package app.soundcheck.media;

import io.swagger.v3.oas.annotations.media.Schema;

/** What a client needs to show or play an asset. URLs are present once READY. */
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
