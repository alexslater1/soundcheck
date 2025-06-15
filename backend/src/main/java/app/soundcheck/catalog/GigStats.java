package app.soundcheck.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A fan's live-music stats, counted from the gigs they marked WENT that have happened
 * (started, neither cancelled nor postponed, with a bill). Every act on a gig's bill counts, support acts too, and an act counts once per
 * gig: two gigs on one night are two times seen.
 *
 * @param currentYear the year it is now (UK time), so a client can compare this year with last year
 *     even when one of them has no gigs and is missing from {@code years}
 * @param years every year with at least one gig, newest first (a gig's year is its venue's local year)
 * @param acts the most-seen acts: most times first, then the most recently seen
 * @param venues the most-visited venues, in the same order
 */
public record GigStats(
        StatsTotals totals, int currentYear, List<StatsYear> years, List<StatsAct> acts, List<StatsVenue> venues) {

    /** Gigs, distinct acts, distinct venues and distinct venue cities, all time. */
    public record StatsTotals(int gigs, int acts, int venues, int cities) {}

    public record StatsYear(int year, int gigs, int acts, int venues) {}

    /** An act with how many of the fan's gigs had it on the bill, and the first and last of them. */
    public record StatsAct(
            UUID id, String name, @Schema(nullable = true) String imageUrl, int times, Instant first, Instant last) {}

    /** A venue with how many of the fan's gigs were there, and the first and last of them. */
    public record StatsVenue(
            UUID id, String name, @Schema(nullable = true) String city, int times, Instant first, Instant last) {}
}
