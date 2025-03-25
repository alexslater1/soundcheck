package app.soundcheck.media.internal.transcode;

import org.jspecify.annotations.Nullable;

/**
 * What the worker needs to know about an uploaded video, from ffprobe. {@code width}/{@code height} are
 * the stored (coded) size; {@code rotation} is the clockwise display rotation in degrees (0, 90, 180,
 * 270) that phones record instead of rotating the pixels.
 */
public record ProbeResult(
        int width,
        int height,
        int rotation,
        long durationMs,
        String videoCodec,
        boolean hasAudio,
        @Nullable String audioCodec,
        @Nullable Double frameRate) {

    /** Width as a viewer sees it: a 1280×720 clip rotated by 90° is 720 wide. */
    public int displayWidth() {
        return sideways() ? height : width;
    }

    public int displayHeight() {
        return sideways() ? width : height;
    }

    public int longEdge() {
        return Math.max(width, height);
    }

    private boolean sideways() {
        return rotation == 90 || rotation == 270;
    }
}
