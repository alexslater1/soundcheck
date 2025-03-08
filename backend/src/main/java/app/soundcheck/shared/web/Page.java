package app.soundcheck.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The one list shape of the API: items plus an opaque cursor for the next page, or
 * {@code null} when there is none.
 */
public record Page<T>(List<T> items, @Schema(nullable = true) String nextCursor) {

    public static <T> Page<T> of(List<T> items) {
        return new Page<>(items, null);
    }
}
