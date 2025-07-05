package app.soundcheck.ingestion.internal.source;

import java.time.Instant;
import java.util.stream.Stream;

/**
 * A place gigs come from: Ticketmaster, venues' own websites (one VENUE
 * source), recorded fixtures; Skiddle when its key arrives. An adapter hides paging, rate limits and the
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

    /** True when this source replays invented data (the committed fixtures), not real listings. */
    default boolean synthetic() {
        return false;
    }
}
