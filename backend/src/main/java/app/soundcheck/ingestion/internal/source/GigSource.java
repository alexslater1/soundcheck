package app.soundcheck.ingestion.internal.source;

import java.time.Instant;
import java.util.stream.Stream;

/**
 * A place gigs come from: Ticketmaster, recorded fixtures, later Skiddle. An adapter
 * hides paging, rate limits and the source's JSON; the rest of ingestion only sees {@link SourceGig}s.
 */
public interface GigSource {

    /** Provenance name stored in {@code catalog.gig_source.source}, e.g. {@code TICKETMASTER}. */
    String name();

    /**
     * Pages of gigs starting in {@code [from, to)}. The stream is lazy: consuming the next page makes the
     * next call(s). It ends early when {@code budget} runs out (check {@link CallBudget#exhausted()}).
     */
    Stream<SourcePage> pages(Instant from, Instant to, CallBudget budget);
}
