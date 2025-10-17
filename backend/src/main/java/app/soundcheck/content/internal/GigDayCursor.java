package app.soundcheck.content.internal;

import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Cursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The keyset cursor of a profile in gig order: {@code [day, timestamp, id]}, base64url-encoded JSON like
 * {@link Cursor}, with the gig day in front. A post not from a gig has no day; it pages as {@link #NO_DAY},
 * which puts it after every gig. A {@link Cursor} (two parts) does not decode as this kind (three), so a
 * cursor from the other order is a {@code 400 CURSOR_INVALID}, not a page from the wrong place.
 */
record GigDayCursor(LocalDate day, Instant at, UUID id) {

    /** The day a post with no gig sorts as: before every gig day the API stores (see {@link Cursor#storable}). */
    static final LocalDate NO_DAY = LocalDate.of(1900, 1, 1);

    /** The cursor after this row; {@code at} is the time the list orders by within a day. */
    static GigDayCursor after(PostRow row, Instant at) {
        return new GigDayCursor(row.day() == null ? NO_DAY : row.day(), at, row.id());
    }

    String encode() {
        String json = "[\"" + day + "\",\"" + at + "\",\"" + id + "\"]";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Null or blank means "first page". */
    static @Nullable GigDayCursor decode(@Nullable String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            if (!json.startsWith("[\"") || !json.endsWith("\"]")) {
                throw invalid();
            }
            String[] parts = json.substring(2, json.length() - 2).split("\",\"", -1);
            if (parts.length != 3) {
                throw invalid();
            }
            LocalDate day = LocalDate.parse(parts[0]);
            if (day.isBefore(NO_DAY) || day.getYear() >= 2200) {
                throw invalid();
            }
            return new GigDayCursor(day, Cursor.storable(Instant.parse(parts[1])), UUID.fromString(parts[2]));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return ApiException.badRequest("CURSOR_INVALID", "The cursor is not valid");
    }
}
