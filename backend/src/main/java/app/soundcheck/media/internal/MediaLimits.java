package app.soundcheck.media.internal;

import java.time.Duration;
import org.springframework.util.unit.DataSize;

/**
 * The upload limits in words, taken from the configured values, so that a refusal always
 * names the limit the server really applies.
 */
public final class MediaLimits {

    private static final long GB = DataSize.ofGigabytes(1).toBytes();
    private static final long MB = DataSize.ofMegabytes(1).toBytes();

    private MediaLimits() {}

    /**
     * "3 minutes" for a limit of 181 s, "60 seconds" for 61 s: a limit one second over a whole minute is
     * that minute plus the second of tolerance for a clip the camera stopped a little late.
     */
    public static String clipLength(Duration max) {
        long seconds = max.toSeconds();
        long minutes = seconds / 60;
        if (minutes >= 2 && seconds % 60 <= 1) {
            return minutes + " minutes";
        }
        long whole = seconds % 60 == 1 ? seconds - 1 : seconds;
        return whole + " seconds";
    }

    /** "1 GB", "500 MB". */
    public static String videoSize(DataSize max) {
        long bytes = max.toBytes();
        return bytes % GB == 0 ? bytes / GB + " GB" : bytes / MB + " MB";
    }
}
