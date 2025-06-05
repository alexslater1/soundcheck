package app.soundcheck.ingestion.internal.resolve;

import app.soundcheck.ingestion.internal.source.TimePrecision;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Decides whether an incoming listing and an existing gig at the same venue are the same show.
 * Pure: the resolver gathers the facts, this class decides, so
 * every rule is a table test.
 *
 * <p>Rules, in order:
 * <ol>
 *   <li>Same UK <em>night</em> (06:00 to 06:00), or it is not the same show.
 *   <li>Within one source the source's own ids mean something: two of its listings on one night are
 *       one show only at the same instant in the same room, or when one is a package listing (the
 *       existing rule). A venue's own site lists each show once, so for it they are always two shows.
 *   <li>When both sides give a real start time, more than 3 h apart is an early and a late show.
 *       A doors time may be up to 4 h before the show; a date-only listing fits anywhere in the night.
 *   <li>A shared act (by catalog id) is the same show.
 *   <li>Otherwise a cleaned title at least {@value #TITLE_SAME} similar is the same show, but only when
 *       one side has no resolved acts: when both name their acts and share none, a similar title
 *       ("Pale Kite" / "Pale Kites") is two artists until someone says otherwise, so it is a MAYBE.
 *   <li>Almost-matches are {@link Verdict#MAYBE}: logged for review, never merged.
 * </ol>
 */
public final class GigMatcher {

    static final ZoneId UK = ZoneId.of("Europe/London");
    static final Duration NIGHT_STARTS = Duration.ofHours(6);
    static final Duration EXACT_WINDOW = Duration.ofHours(3);
    static final Duration DOORS_WINDOW = Duration.ofHours(4);
    static final double TITLE_SAME = 0.6;
    static final double TITLE_MAYBE = 0.3;
    static final double ACT_MAYBE = 0.5;

    private GigMatcher() {}

    public enum Verdict {
        SAME,
        MAYBE,
        DIFFERENT
    }

    /** One side of a comparison. {@code actNames} are in billing order, matching {@code actIds}. */
    public record Side(
            String source,
            Instant startsAt,
            TimePrecision precision,
            String title,
            List<UUID> actIds,
            List<String> actNames,
            boolean packageListing) {}

    public record Decision(Verdict verdict, String reason, double titleSimilarity, double actSimilarity) {}

    /** Sources that list each show exactly once: a venue's own site has one page per show. */
    static final Set<String> ONE_LISTING_PER_SHOW = Set.of("VENUE");

    /** Like {@link #decide(Side, Side, Collection, boolean)} for a gig in the same room. */
    public static Decision decide(Side incoming, Side existing, Collection<String> existingSources) {
        return decide(incoming, existing, existingSources, true);
    }

    /**
     * @param incoming the listing being ingested
     * @param existing the gig as its primary listing describes it
     * @param existingSources every source that already lists the gig
     * @param sameRoom whether the gig is at the incoming listing's venue, not at a sibling of it
     */
    public static Decision decide(Side incoming, Side existing, Collection<String> existingSources, boolean sameRoom) {
        if (!night(incoming.startsAt()).equals(night(existing.startsAt()))) {
            return new Decision(Verdict.DIFFERENT, "different night", 0, 0);
        }
        if (existingSources.contains(incoming.source())) {
            // A venue's site lists each show once, so two of its listings are two shows, even when both
            // rooms open at 19:00. Another source lists one show twice only in the same room.
            boolean same = !ONE_LISTING_PER_SHOW.contains(incoming.source())
                    && sameRoom
                    && (incoming.startsAt().equals(existing.startsAt())
                            || incoming.packageListing()
                            || existing.packageListing());
            return new Decision(
                    same ? Verdict.SAME : Verdict.DIFFERENT,
                    same ? "same source, same instant or a package listing" : "same source, another show",
                    0,
                    0);
        }
        Duration apart =
                Duration.between(incoming.startsAt(), existing.startsAt()).abs();
        if (apart.compareTo(window(incoming.precision(), existing.precision())) > 0) {
            return new Decision(Verdict.DIFFERENT, "starts " + apart.toMinutes() + " min apart", 0, 0);
        }
        Set<UUID> shared = new HashSet<>(incoming.actIds());
        shared.retainAll(existing.actIds());
        double titles = Trigrams.similarity(clean(incoming), clean(existing));
        double acts = bestActSimilarity(incoming.actNames(), existing.actNames());
        if (!shared.isEmpty()) {
            return new Decision(Verdict.SAME, "shared act", titles, 1.0);
        }
        boolean bothNameActs =
                !incoming.actIds().isEmpty() && !existing.actIds().isEmpty();
        if (titles >= TITLE_SAME && !bothNameActs) {
            return new Decision(Verdict.SAME, "similar title", titles, acts);
        }
        if (titles >= TITLE_MAYBE || acts >= ACT_MAYBE) {
            return new Decision(Verdict.MAYBE, "almost: similar names, no shared act", titles, acts);
        }
        return new Decision(Verdict.DIFFERENT, "nothing in common", titles, acts);
    }

    /** The UK night a start belongs to: 00:30 on Saturday is still Friday night. */
    static LocalDate night(Instant startsAt) {
        return startsAt.atZone(UK).minus(NIGHT_STARTS).toLocalDate();
    }

    /** How far apart two starts may be: 3 h between real times, 4 h when one is doors, any when date-only. */
    static Duration window(TimePrecision a, TimePrecision b) {
        if (a == TimePrecision.DATE_ONLY || b == TimePrecision.DATE_ONLY) {
            return Duration.ofHours(24);
        }
        if (a == TimePrecision.DOORS || b == TimePrecision.DOORS) {
            return DOORS_WINDOW;
        }
        return EXACT_WINDOW;
    }

    /** The title with status words, promoters and tour names removed (the acts part). */
    static String clean(Side side) {
        String whole = TitleParser.parse(side.title()).whole();
        return whole.isBlank() ? side.title() : whole;
    }

    static double bestActSimilarity(List<String> a, List<String> b) {
        double best = 0;
        for (String x : a) {
            for (String y : b) {
                best = Math.max(best, Trigrams.similarity(x, y));
            }
        }
        return best;
    }
}
