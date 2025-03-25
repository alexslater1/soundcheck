package app.soundcheck.media.internal.transcode;

import java.util.ArrayList;
import java.util.List;

/**
 * One rendition of the HLS ladder. {@code longEdge} is the size of the longer side, so the
 * same rung is 640×360 for landscape and 360×640 for portrait.
 */
public enum Rung {
    P360("360p", 640, 800, 856, 1200, "main"),
    P540("540p", 960, 1600, 1712, 2400, "high"),
    P720("720p", 1280, 3000, 3210, 4500, "high");

    public final String label;
    public final int longEdge;
    public final int bitrateK;
    public final int maxrateK;
    public final int bufsizeK;
    public final String profile;

    Rung(String label, int longEdge, int bitrateK, int maxrateK, int bufsizeK, String profile) {
        this.label = label;
        this.longEdge = longEdge;
        this.bitrateK = bitrateK;
        this.maxrateK = maxrateK;
        this.bufsizeK = bufsizeK;
        this.profile = profile;
    }

    /** A rung with the output size it has for one particular source. */
    public record Sized(Rung rung, int width, int height) {}

    /**
     * The rungs for a source: never upscale (a rung larger than the source is dropped), but always keep
     * the lowest, at the source's own size if the source is smaller still.
     */
    public static List<Sized> ladderFor(ProbeResult probe) {
        List<Sized> ladder = new ArrayList<>();
        for (Rung rung : values()) {
            if (rung.longEdge <= probe.longEdge() || ladder.isEmpty()) {
                ladder.add(rung.sizedFor(probe));
            }
        }
        return ladder;
    }

    /** Scales the displayed frame so its longer side is this rung's (or the source's, if smaller); sides are even. */
    Sized sizedFor(ProbeResult probe) {
        int w = probe.displayWidth();
        int h = probe.displayHeight();
        // Rounded down to even when the source's own (odd) size is used: 333 → 332, never 334.
        int target = Math.min(longEdge, Math.max(w, h)) / 2 * 2;
        return w >= h
                ? new Sized(this, even(target), even((double) target * h / w))
                : new Sized(this, even((double) target * w / h), even(target));
    }

    /** H.264 with 4:2:0 chroma needs even dimensions. */
    private static int even(double value) {
        return Math.max(2, (int) Math.round(value / 2) * 2);
    }
}
