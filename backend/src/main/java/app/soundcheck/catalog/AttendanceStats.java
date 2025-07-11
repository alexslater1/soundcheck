package app.soundcheck.catalog;

import app.soundcheck.shared.web.Page;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Stats over a user's WENT gigs, for profiles. The catalog counts; the identity module,
 * which owns profiles and the user's choice to share them, decides who may ask.
 */
public interface AttendanceStats {

    /** The most acts and venues listed in one {@link GigStats}. */
    int MAX_TOP = 100;

    /** The user's stats with the top {@code top} acts and venues (1 to {@link #MAX_TOP}). */
    GigStats of(UUID userId, int top);

    /**
     * The gigs behind one line of the stats: the user's WENT gigs with the act on the bill, or at the
     * venue (or every WENT gig when both are null), newest first. Attendance in the summaries is the
     * viewer's own, as everywhere else.
     */
    Page<GigSummary> wentGigs(
            UUID viewerId,
            UUID userId,
            @Nullable UUID artistId,
            @Nullable UUID venueId,
            @Nullable String cursor,
            @Nullable Integer limit);

    /**
     * The gigs the user is going to (the Gigs tab of their profile): marked GOING and still to
     * come, soonest first. INTERESTED stays private to the user. Attendance in the summaries is the
     * viewer's own.
     */
    Page<GigSummary> goingGigs(UUID viewerId, UUID userId, @Nullable String cursor, @Nullable Integer limit);

    /** How many gigs {@link #wentGigs} lists in all, across every page: "You've seen Keo 5 times". */
    int wentCount(UUID userId, @Nullable UUID artistId, @Nullable UUID venueId);
}
