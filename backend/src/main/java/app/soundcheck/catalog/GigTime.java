package app.soundcheck.catalog;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The one definition of where a gig stands in time. Gigs are UK events, so a gig's day is its
 * start's date on the UK calendar, wherever the phone or the server is.
 *
 * <ul>
 *   <li><b>Upcoming</b>: its day is still to come. Interested and Going are allowed; Went is not.
 *   <li><b>On today</b>: its day is today. All three are allowed: a fan at the gig may say Went, a fan on
 *       the way may still say Going.
 *   <li><b>Over</b>: its day has ended. Only Went is allowed, and a Going reads as Went.
 * </ul>
 *
 * "Its day has come" (on today or over) is when a gig counts as having happened: Went, a post from it,
 * its clips section, the stats and "Times seen" all start then.
 */
public final class GigTime {

    public static final ZoneId UK = ZoneId.of("Europe/London");

    private GigTime() {}

    /** The UK calendar day {@code now} falls on. */
    public static LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, UK);
    }

    /** The UK calendar day a gig starting at {@code startsAt} is on. */
    public static LocalDate dayOf(Instant startsAt) {
        return LocalDate.ofInstant(startsAt, UK);
    }

    /** When today began on the UK clock: a gig starting before this is over. */
    public static Instant startOfToday(Instant now) {
        return today(now).atStartOfDay(UK).toInstant();
    }

    /** When tomorrow begins on the UK clock: a gig starting before this has had its day come. */
    public static Instant startOfTomorrow(Instant now) {
        return today(now).plusDays(1).atStartOfDay(UK).toInstant();
    }

    /** The gig's day is today or has passed. */
    public static boolean dayHasCome(Instant startsAt, Instant now) {
        return startsAt.isBefore(startOfTomorrow(now));
    }

    /** The gig's day has ended. */
    public static boolean isOver(Instant startsAt, Instant now) {
        return startsAt.isBefore(startOfToday(now));
    }
}
