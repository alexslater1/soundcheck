package app.soundcheck.catalog.internal;

import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ATTENDANCE;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE;

import app.soundcheck.catalog.GigStats.StatsAct;
import app.soundcheck.catalog.GigStats.StatsTotals;
import app.soundcheck.catalog.GigStats.StatsVenue;
import app.soundcheck.catalog.GigStats.StatsYear;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * The counting behind a fan's stats, over their WENT rows of {@code catalog.gig_attendance}.
 * Expected indexes: the attendance primary key {@code (user_id, gig_id)} finds one user's rows; gigs,
 * venues and artists are joined by primary key and the bills by {@code gig_artist}'s primary key
 * {@code (gig_id, artist_id)}. One fan has at most a few hundred WENT gigs, so each query groups a few
 * thousand rows at most.
 */
@Repository
class AttendanceStatsRepository {

    /** A gig's year in its venue's time zone: a New Year's Eve gig in London belongs to the old year. */
    static final Field<Integer> YEAR =
            DSL.field("extract(year from {0} at time zone {1})::int", Integer.class, GIG.STARTS_AT, VENUE.TIMEZONE);

    /** All-time totals and one row per year, from one query. */
    record Counts(StatsTotals totals, List<StatsYear> years) {}

    private final DSLContext dsl;

    AttendanceStatsRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Totals and per-year counts. {@code GROUP BY ROLLUP (year)} returns a row per year and one more
     * for all years together (where {@code grouping(year)} is 1), so the distinct counts of the total are
     * real distinct counts, not sums of the years'. Only gigs with a bill count, like everywhere a gig is
     * listed (a gig without one is never shown), so the totals agree with the gigs behind them.
     */
    Counts counts(UUID userId, Instant now) {
        Field<Integer> total = DSL.grouping(YEAR);
        Field<Integer> gigs = DSL.countDistinct(GIG.ID);
        Field<Integer> acts = DSL.countDistinct(GIG_ARTIST.ARTIST_ID);
        Field<Integer> venues = DSL.countDistinct(GIG.VENUE_ID);
        // "London" and "london " from two sources are one city.
        Field<Integer> cities = DSL.countDistinct(DSL.lower(DSL.trim(VENUE.CITY)));
        StatsTotals totals = new StatsTotals(0, 0, 0, 0);
        List<StatsYear> years = new ArrayList<>();
        var rows = dsl.select(YEAR, total, gigs, acts, venues, cities)
                .from(GIG_ATTENDANCE)
                .join(GIG)
                .on(GIG.ID.eq(GIG_ATTENDANCE.GIG_ID))
                .join(VENUE)
                .on(VENUE.ID.eq(GIG.VENUE_ID))
                .join(GIG_ARTIST)
                .on(GIG_ARTIST.GIG_ID.eq(GIG.ID))
                .where(went(userId, now))
                .groupBy(DSL.rollup(YEAR))
                .orderBy(YEAR.desc())
                .fetch();
        for (var row : rows) {
            if (row.get(total) == 1) {
                totals = new StatsTotals(row.get(gigs), row.get(acts), row.get(venues), row.get(cities));
            } else {
                years.add(new StatsYear(row.get(YEAR), row.get(gigs), row.get(acts), row.get(venues)));
            }
        }
        return new Counts(totals, years);
    }

    /**
     * The acts on the most of the user's gigs. {@code count(*)} counts gigs because a gig lists an act at
     * most once ({@code gig_artist}'s primary key); grouping by the artist's primary key lets the query
     * select its name and image.
     */
    List<StatsAct> topActs(UUID userId, Instant now, int limit) {
        Field<Integer> times = DSL.count();
        Field<OffsetDateTime> first = DSL.min(GIG.STARTS_AT);
        Field<OffsetDateTime> last = DSL.max(GIG.STARTS_AT);
        return dsl.select(ARTIST.ID, ARTIST.NAME, ARTIST.IMAGE_URL, times, first, last)
                .from(GIG_ATTENDANCE)
                .join(GIG)
                .on(GIG.ID.eq(GIG_ATTENDANCE.GIG_ID))
                .join(GIG_ARTIST)
                .on(GIG_ARTIST.GIG_ID.eq(GIG.ID))
                .join(ARTIST)
                .on(ARTIST.ID.eq(GIG_ARTIST.ARTIST_ID))
                .where(went(userId, now))
                .groupBy(ARTIST.ID)
                .orderBy(times.desc(), last.desc(), ARTIST.NAME.asc(), ARTIST.ID.asc())
                .limit(limit)
                .fetch(r -> new StatsAct(
                        r.value1(),
                        r.value2(),
                        r.value3(),
                        r.value4(),
                        r.value5().toInstant(),
                        r.value6().toInstant()));
    }

    /** The venues of the most of the user's gigs, in the same order as {@link #topActs}. */
    List<StatsVenue> topVenues(UUID userId, Instant now, int limit) {
        Field<Integer> times = DSL.count();
        Field<OffsetDateTime> first = DSL.min(GIG.STARTS_AT);
        Field<OffsetDateTime> last = DSL.max(GIG.STARTS_AT);
        return dsl.select(VENUE.ID, VENUE.NAME, VENUE.CITY, times, first, last)
                .from(GIG_ATTENDANCE)
                .join(GIG)
                .on(GIG.ID.eq(GIG_ATTENDANCE.GIG_ID))
                .join(VENUE)
                .on(VENUE.ID.eq(GIG.VENUE_ID))
                .where(went(userId, now))
                .groupBy(VENUE.ID)
                .orderBy(times.desc(), last.desc(), VENUE.NAME.asc(), VENUE.ID.asc())
                .limit(limit)
                .fetch(r -> new StatsVenue(
                        r.value1(),
                        r.value2(),
                        r.value3(),
                        r.value4(),
                        r.value5().toInstant(),
                        r.value6().toInstant()));
    }

    /**
     * The gigs that count: marked WENT, started already, and not cancelled. The app offers Went on any
     * gig, so a tap on next month's show, or on one cancelled later, must not count as a gig seen.
     */
    private static Condition went(UUID userId, Instant now) {
        return GIG_ATTENDANCE
                .USER_ID
                .eq(userId)
                .and(GIG_ATTENDANCE.STATUS.eq("WENT"))
                .and(GIG.STARTS_AT.lt(now.atOffset(ZoneOffset.UTC)))
                .and(GIG.STATUS.ne("CANCELLED"));
    }
}
