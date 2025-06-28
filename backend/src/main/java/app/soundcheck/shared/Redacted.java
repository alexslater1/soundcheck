package app.soundcheck.shared;

import org.jspecify.annotations.Nullable;

/**
 * What a configuration record's {@code toString} prints instead of a secret (a key, a password, a PEM):
 * only whether one is set, so a logged or printed configuration never carries the value.
 */
public final class Redacted {

    private Redacted() {}

    /** {@code <none>} when absent or blank, otherwise {@code <redacted>}; never the value itself. */
    public static String of(@Nullable String secret) {
        return secret == null || secret.isBlank() ? "<none>" : "<redacted>";
    }
}
