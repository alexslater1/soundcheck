package app.soundcheck.shared.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Keyset cursors: the sort key of the last row a page returned, base64url-encoded JSON. The
 * next page asks for rows <em>after</em> that key, so the database walks an index instead of counting an
 * OFFSET, and rows inserted meanwhile never shift the pages.
 *
 * <p>This is the time-ordered kind: {@code [timestamp, id]} — the id breaks ties between equal timestamps.
 * Cursors are opaque to clients; a cursor that does not decode is a {@code 400 CURSOR_INVALID}.
 */
public record Cursor(Instant at, UUID id) {

    public String encode() {
        String json = "[\"" + at + "\",\"" + id + "\"]";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Null or blank means "first page". */
    public static @Nullable Cursor decode(@Nullable String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            // Exactly the shape encode() writes: ["<instant>","<uuid>"].
            if (!json.startsWith("[\"") || !json.endsWith("\"]")) {
                throw invalid();
            }
            String[] parts = json.substring(2, json.length() - 2).split("\",\"", -1);
            if (parts.length != 2) {
                throw invalid();
            }
            return new Cursor(storable(Instant.parse(parts[0])), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid();
        }
    }

    /**
     * Rejects instants outside what the database can store ({@code Instant} reaches year ±1,000,000,000;
     * PostgreSQL's timestamptz does not). The API only deals with gig dates, so 1900–2200 is generous.
     *
     * @throws IllegalArgumentException for an out-of-range instant
     */
    public static Instant storable(Instant instant) {
        if (instant.isBefore(MIN) || instant.isAfter(MAX)) {
            throw new IllegalArgumentException("instant out of range");
        }
        return instant;
    }

    private static final Instant MIN = Instant.parse("1900-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("2200-01-01T00:00:00Z");

    /** Page size: 20 when absent, otherwise clamped to 1..50. */
    public static int limit(@Nullable Integer requested) {
        if (requested == null) {
            return 20;
        }
        return Math.max(1, Math.min(50, requested));
    }

    private static ApiException invalid() {
        return ApiException.badRequest("CURSOR_INVALID", "The cursor is not valid");
    }
}
