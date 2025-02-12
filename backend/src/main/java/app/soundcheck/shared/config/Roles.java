package app.soundcheck.shared.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** The process roles and the parsing of the {@code soundcheck.roles} property. */
public final class Roles {

    public static final String PROPERTY = "soundcheck.roles";
    public static final String API = "api";
    public static final String SCHEDULER = "scheduler";
    public static final String WORKER = "worker";

    private Roles() {}

    /** Parses a comma-separated list, ignoring case, whitespace and empty entries. */
    public static Set<String> parse(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
