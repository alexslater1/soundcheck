package app.soundcheck.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An existing gig as ingestion needs to see it: its id, stored content hash, title, start, and the
 * listings that point at it.
 */
public record GigRef(UUID id, byte[] contentHash, String title, Instant startsAt, List<Listing> listings) {

    /**
     * One {@code catalog.gig_source} row: a source and the id it uses for this gig. Exactly one listing per
     * gig is {@code primary}: the only one allowed to rewrite the gig's fields.
     */
    public record Listing(String source, String externalId, boolean primary, Instant lastSeenAt) {}

    public Listing primary() {
        return listings.stream().filter(Listing::primary).findFirst().orElseThrow();
    }

    public boolean listedBy(String source) {
        return listings.stream().anyMatch(l -> l.source().equals(source));
    }
}
