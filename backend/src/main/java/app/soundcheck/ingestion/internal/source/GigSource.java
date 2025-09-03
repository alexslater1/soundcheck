package app.soundcheck.ingestion.internal.source;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * A place gigs come from: Ticketmaster, venues' own websites (one VENUE
 * source), recorded fixtures, setlist.fm for past gigs; Skiddle when its key arrives. An adapter hides paging, rate limits and the
 * source's format; the rest of ingestion only sees {@link SourceGig}s.
 */
public interface GigSource {

    /** Provenance name stored in {@code catalog.gig_source.source}, e.g. {@code TICKETMASTER}. */
    String name();

    /**
     * Pages of gigs starting in {@code [from, to)}. The stream is lazy: consuming the next page makes the
     * next call(s). It ends early when {@code budget} runs out (check {@link CallBudget#exhausted()}).
     */
    Stream<SourcePage> pages(Instant from, Instant to, CallBudget budget);

    /**
     * The start times one run asks for: from today to the horizon for sources of upcoming gigs; a source
     * of past gigs (setlist.fm) looks back instead.
     */
    default Window window(Instant today, Duration horizon) {
        return new Window(today, today.plus(horizon));
    }

    /** {@code [from, to)}. */
    record Window(Instant from, Instant to) {

        /** True when the window starts today or later: its gigs should not have started yet. */
        public boolean upcoming(Instant today) {
            return !from.isBefore(today);
        }
    }

    /** True when this source replays invented data (the committed fixtures), not real listings. */
    default boolean synthetic() {
        return false;
    }
}
