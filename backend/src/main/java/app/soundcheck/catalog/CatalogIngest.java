package app.soundcheck.catalog;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The catalog's write API for the ingestion module.
 * Catalog owns the tables and the SQL (lookups by external id, fuzzy and geo matching primitives, inserts
 * and updates); ingestion owns the decisions (which rule to try in which order, with which thresholds).
 * Methods join the caller's transaction.
 */
public interface CatalogIngest {

    // ── venues ──────────────────────────────────────────────────────────────

    Optional<UUID> venueByExternalId(String source, String externalId);

    /** The most similarly named venue within {@code metres} of the point, if its similarity reaches the minimum. */
    Optional<UUID> venueNear(double lat, double lng, int metres, String name, double minSimilarity);

    /** The most similarly named venue with the same postcode, if its similarity reaches the minimum. */
    Optional<UUID> venueByPostcode(String postcode, String name, double minSimilarity);

    UUID createVenue(VenueDraft venue);

    /** Records that {@code source} calls this venue {@code externalId} (idempotent). */
    void linkVenue(String source, String externalId, UUID venueId);

    // ── artists ─────────────────────────────────────────────────────────────

    Optional<UUID> artistByExternalId(String source, String externalId);

    Optional<UUID> artistByMbid(UUID mbid);

    /** An artist whose normalised name equals the normalised {@code name}. */
    Optional<UUID> artistByName(String name);

    /**
     * The most similarly named artist reaching {@code minSimilarity} that also shares one of the genres or
     * has played the given venue: a near-identical name alone is not enough (tribute acts).
     */
    Optional<UUID> artistSimilar(String name, double minSimilarity, Collection<Short> genreIds, UUID venueId);

    UUID createArtist(ArtistDraft artist);

    /** Records that {@code source} calls this artist {@code externalId} (idempotent). */
    void linkArtist(String source, String externalId, UUID artistId);

    /** Fills in a MusicBrainz id or an image an existing artist does not have yet; never overwrites. */
    void completeArtist(UUID artistId, @Nullable UUID mbid, @Nullable String imageUrl);

    /** Genre ids and alias weights for source labels or tags (case-insensitive); unknown labels are absent. */
    Map<String, GenreMatch> genresForAliases(Collection<String> aliases);

    /** One alias hit: the canonical genre and how strongly the alias implies it. */
    record GenreMatch(short genreId, float weight) {}

    // ── gigs ────────────────────────────────────────────────────────────────

    Optional<GigRef> gigBySource(String source, String externalId);

    /**
     * A gig at the same venue starting within {@code window} of {@code startsAt} that has the same
     * headliner, or failing that a title at least {@code minTitleSimilarity} similar.
     */
    Optional<GigRef> gigAtVenueAround(
            UUID venueId, Instant startsAt, Duration window, UUID headlinerId, String title, double minTitleSimilarity);

    UUID createGig(GigDraft gig);

    /** Replaces the gig's fields and line-up. */
    void updateGig(UUID gigId, GigDraft gig);

    /** Records that {@code source} lists this gig as {@code externalId} and saw it at {@code seenAt} (upsert). */
    void linkGig(String source, String externalId, UUID gigId, @Nullable String url, Instant seenAt);

    /** External ids of {@code source}'s future gigs last seen before {@code before}. */
    List<String> futureGigsNotSeenSince(String source, Instant before, Instant now, int limit);
}
