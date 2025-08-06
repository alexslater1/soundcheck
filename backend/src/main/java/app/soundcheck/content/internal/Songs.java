package app.soundcheck.content.internal;

import org.jspecify.annotations.Nullable;

/**
 * The song tagged on a clip: free text the user typed, with no lookup behind it. At most
 * 120 characters (the requests' {@code @Size} and the column's check).
 */
final class Songs {

    private Songs() {}

    /** The song as stored: trimmed, and null when nothing is left. */
    static @Nullable String clean(@Nullable String song) {
        if (song == null) {
            return null;
        }
        String trimmed = song.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
