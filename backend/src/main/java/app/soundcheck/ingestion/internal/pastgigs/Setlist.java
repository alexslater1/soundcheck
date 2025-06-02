package app.soundcheck.ingestion.internal.pastgigs;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * The songs played at a past gig, as setlist.fm has them. Looked up live for the gig's page
 * and cached for an hour at most: setlist.fm's terms allow only short caching, so it is never
 * stored. {@code url} is the setlist's page on setlist.fm, which the app must link to.
 */
public record Setlist(
        String id,
        @Schema(nullable = true) String url,
        String artist,
        String venue,
        @Schema(nullable = true) String city,
        LocalDate date,
        @Schema(nullable = true) String tour,
        List<SetlistPart> sets) {

    /** One part of the show: the main set (no title), a named set, or an encore. */
    public record SetlistPart(@Schema(nullable = true) String title, List<SetlistSong> songs) {}

    /** {@code coverOf}: the original artist when the song is a cover; {@code tape}: played from a tape. */
    public record SetlistSong(
            String name, @Schema(nullable = true) String info, @Schema(nullable = true) String coverOf, boolean tape) {}
}
