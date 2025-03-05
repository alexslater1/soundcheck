package app.soundcheck.catalog;

import java.util.List;
import java.util.UUID;

/** An existing gig as ingestion needs to see it: its id, stored content hash and the listings that point at it. */
public record GigRef(UUID id, byte[] contentHash, List<Listing> listings) {

    /** One {@code catalog.gig_source} row: a source and the id it uses for this gig. */
    public record Listing(String source, String externalId) {}
}
