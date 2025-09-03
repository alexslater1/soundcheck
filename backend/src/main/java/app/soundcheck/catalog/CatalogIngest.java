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

    /**
     * Records that two venues are the same building (rooms, or one venue a source lists twice), so the
     * same-show search covers both. Every sibling of either becomes a sibling of the other
     * (idempotent).
     */
    void linkSiblings(UUID venueId, UUID siblingId);

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

    /**
     * Whether something other than a parsed title vouches for this artist: a MusicBrainz id or an id at
     * a source (only such artists may undo a title split, so a wrong parse cannot confirm itself).
     */
    boolean artistIsVouchedFor(UUID artistId);

    /** Records that {@code source} calls this artist {@code externalId} (idempotent). */
    void linkArtist(String source, String externalId, UUID artistId);

    /** Fills in a MusicBrainz id or an image an existing artist does not have yet; never overwrites. */
    void completeArtist(UUID artistId, @Nullable UUID mbid, @Nullable String imageUrl);

    /** Artists never enriched, or last enriched before {@code staleBefore}; never-enriched first. */
    List<ArtistToEnrich> artistsToEnrich(Instant staleBefore, int limit);

    /** What enrichment needs to know about an artist. */
    record ArtistToEnrich(UUID id, String name, @Nullable UUID mbid) {}

    /**
     * Every act on the bill of a gig that has a published clip, by name. These are the acts whose
     * past gigs the setlist.fm backfill looks up.
     */
    List<BilledArtist> artistsOnGigsWithClips();

    /** An act and what setlist.fm can be asked by: its MusicBrainz id when known, else its name. */
    record BilledArtist(UUID id, String name, @Nullable UUID mbid) {}

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

    /**
     * Gigs at the venue or one of its siblings starting in {@code [from, to)}, with their bills (the
     * matcher decides which, if any, is the same show; this only gathers them). Expected indexes:
     * venue_sibling_pkey, gig_venue.
     */
    List<GigCandidate> gigsAtVenueBetween(UUID venueId, Instant from, Instant to);

    /**
     * Every gig {@code source} lists that starts in {@code [from, to)} and is not cancelled, and whether
     * {@code other} lists it too, by start time. Expected indexes: gig_source_pkey (source
     * prefix), gig_pkey.
     */
    List<ListingOverlap> listingsAlsoListedBy(String source, String other, Instant from, Instant to);

    /**
     * Fills what the gig does not have from a listing that is not its primary one, never overwriting:
     * empty doors time, ticket link, prices, image, and acts missing
     * from the bill (added after the existing ones). Returns whether anything changed.
     */
    boolean fillGig(UUID gigId, GigDraft from);

    UUID createGig(GigDraft gig);

    /** Replaces the gig's fields and line-up. */
    void updateGig(UUID gigId, GigDraft gig);

    /**
     * Records that {@code source} lists this gig as {@code externalId} and saw it at {@code seenAt} (upsert).
     * {@code primary} applies only when the row is new; use {@link #makePrimary} to move ownership.
     * {@code packageListing} (a VIP or premium listing) is refreshed on every call. A gig marked UNLISTED
     * ({@link #unlistNotSeenSince}) is SCHEDULED again: it is listed once more.
     */
    void linkGig(
            String source,
            String externalId,
            UUID gigId,
            @Nullable String url,
            Instant seenAt,
            boolean primary,
            boolean packageListing);

    /** Makes this listing the gig's primary one (and every other listing of the gig non-primary). */
    void makePrimary(UUID gigId, String source, String externalId);

    /** Fills in a venue's coordinates when it had none, and copies them to its gigs that have none. */
    void completeVenue(UUID venueId, @Nullable Double lat, @Nullable Double lng);

    /**
     * Sets the "we don't read this venue's own listings" notes to exactly {@code notes}: a venue
     * no longer listed loses its note. Returns how many venues carry one.
     */
    int applyCoverageNotes(List<VenueNote> notes);

    /**
     * Venues without coordinates, those with gigs still to come first: a venue no list can place is
     * invisible to anyone searching near home.
     */
    List<VenueToLocate> venuesWithoutLocation(Instant now, int limit);

    /** How many listings {@code source} has, optionally only those whose external id starts with a prefix. */
    long countListings(String source, @Nullable String externalIdPrefix);

    /** External ids of {@code source}'s future gigs last seen before {@code before}. */
    List<String> futureGigsNotSeenSince(String source, Instant before, Instant now, int limit);

    /**
     * Marks UNLISTED every scheduled gig still to come that has a listing of {@code source} whose
     * external id starts with {@code externalIdPrefix}, and whose listings are all {@code source}'s and all
     * last seen before {@code before}. A gig another source or a fan also lists is left alone.
     *
     * @return how many gigs were marked
     */
    int unlistNotSeenSince(String source, String externalIdPrefix, Instant before, Instant now);
}
