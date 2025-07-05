package app.soundcheck.social;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Domain event: a user follows an act or a venue, or saved a search, that new gigs match. One per
 * user per alert pass, with every new gig for them (soonest first); consumed by notifications, which
 * turns it into one inbox entry ({@code alertId} keeps that idempotent).
 */
public record NewGigsFound(UUID alertId, UUID userId, List<AlertedGig> gigs, Instant at) {

    /** Why the user hears about this gig. */
    public enum Reason {
        ARTIST,
        VENUE,
        SEARCH
    }

    /**
     * A new gig as it was when the alert was made. {@code because} names what matched: the act, the
     * venue, or the saved search.
     */
    public record AlertedGig(
            UUID gigId,
            String title,
            Instant startsAt,
            String venueName,
            @Nullable String venueCity,
            Reason reason,
            String because) {}
}
