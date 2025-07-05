package app.soundcheck.catalog;

import java.util.Optional;
import java.util.UUID;

/** Catalog's read API for other modules (ids only for now). */
public interface Catalog {

    boolean artistExists(UUID artistId);

    boolean gigExists(UUID gigId);

    boolean venueExists(UUID venueId);

    /** A gig's date, place, acts and setlist.fm id, for looking it up elsewhere; empty if no such gig. */
    Optional<GigFacts> gigFacts(UUID gigId);
}
