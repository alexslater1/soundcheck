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

    /**
     * The most similarly named venue within {@code metres} of the point, if its similarity reaches the
     * minimum. Venues {@code notKnownTo} already knows under another id are skipped: a source's own ids
     * are authoritative within that source (two rooms of one building are two venues to it).
     */
    Optional<UUID> venueNear(double lat, double lng, int metres, String name, double minSimilarity, String notKnownTo);

    /** The most similarly named venue with the same postcode, with the same exclusion as {@link #venueNear}. */
    Optional<UUID> venueByPostcode(String postcode, String name, double minSimilarity, String notKnownTo);

    UUID createVenue(VenueDraft venue);

    /** Records that {@code source} calls this venue {@code externalId} (idempotent). */
    void linkVenue(String source, String externalId, UUID venueId);

    // ── artists ─────────────────────────────────────────────────────────────

    Optional<UUID> artistByExternalId(String source, String externalId);

    Optional<UUID> artistByMbid(UUID mbid);

    /**
     * An artist whose normalised name equals the normalised {@code name}. A candidate that carries a
     * <em>different</em> MusicBrainz id than {@code mbid} is a different act with the same name and is skipped.
     */
    Optional<UUID> artistByName(String name, @Nullable UUID mbid);

    /**
     * The most similarly named artist reaching {@code minSimilarity} that also shares one of the genres or
     * has played the given venue: a near-identical name alone is not enough (tribute acts). Candidates with
     * a different MusicBrainz id than {@code mbid} are skipped.
     */
    Optional<UUID> artistSimilar(
            String name, double minSimilarity, Collection<Short> genreIds, UUID venueId, @Nullable UUID mbid);

    UUID createArtist(ArtistDraft artist);

    /** Records that {@code source} calls this artist {@code externalId} (idempotent). */
    void linkArtist(String source, String externalId, UUID artistId);

    /** Fills in a MusicBrainz id or an image an existing artist does not have yet; never overwrites. */
    void completeArtist(UUID artistId, @Nullable UUID mbid, @Nullable String imageUrl);

    /** Artists never enriched, or last enriched before {@code staleBefore}; never-enriched first. */
    List<ArtistToEnrich> artistsToEnrich(Instant staleBefore, int limit);

    /** What enrichment needs to know about an artist. */
    record ArtistToEnrich(UUID id, String name, @Nullable UUID mbid) {}

    /** Replaces the artist's genres (genre id → weight); an empty map leaves the existing genres alone. */
    void replaceArtistGenres(UUID artistId, Map<Short, Float> genreWeights);

    void markArtistEnriched(UUID artistId, Instant at);

    /** How many of the given artists have at least one genre. */
    int countWithGenres(Collection<UUID> artistIds);

    /** Genre ids and alias weights for source labels or tags (case-insensitive); unknown labels are absent. */
    Map<String, GenreMatch> genresForAliases(Collection<String> aliases);

    /** One alias hit: the canonical genre and how strongly the alias implies it. */
    record GenreMatch(short genreId, float weight) {}

    // ── gigs ────────────────────────────────────────────────────────────────

    Optional<GigRef> gigBySource(String source, String externalId);

    /**
     * Gigs at the same venue starting within {@code window} of {@code startsAt} that have the same
     * headliner or a title at least {@code minTitleSimilarity} similar, nearest in time first. Whether one
     * of them is "the same gig" is the caller's decision.
     */
    List<GigRef> gigsAtVenueAround(
            UUID venueId, Instant startsAt, Duration window, UUID headlinerId, String title, double minTitleSimilarity);

    UUID createGig(GigDraft gig);

    /** Replaces the gig's fields and line-up. */
    void updateGig(UUID gigId, GigDraft gig);

    /**
     * Records that {@code source} lists this gig as {@code externalId} and saw it at {@code seenAt} (upsert).
     * {@code primary} applies only when the row is new; use {@link #makePrimary} to move ownership.
     */
    void linkGig(String source, String externalId, UUID gigId, @Nullable String url, Instant seenAt, boolean primary);

    /** Makes this listing the gig's primary one (and every other listing of the gig non-primary). */
    void makePrimary(UUID gigId, String source, String externalId);

    /** Fills in a venue's coordinates when it had none, and copies them to its gigs that have none. */
    void completeVenue(UUID venueId, @Nullable Double lat, @Nullable Double lng);

    /** How many listings {@code source} has, optionally only those whose external id starts with a prefix. */
    long countListings(String source, @Nullable String externalIdPrefix);

    /** External ids of {@code source}'s future gigs last seen before {@code before}. */
    List<String> futureGigsNotSeenSince(String source, Instant before, Instant now, int limit);
}
