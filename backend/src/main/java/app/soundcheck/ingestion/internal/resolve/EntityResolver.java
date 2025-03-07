package app.soundcheck.ingestion.internal.resolve;

import app.soundcheck.catalog.ArtistDraft;
import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.GigDraft;
import app.soundcheck.catalog.GigRef;
import app.soundcheck.catalog.VenueDraft;
import app.soundcheck.ingestion.internal.RunStats;
import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns one {@link SourceGig} into catalog rows without creating duplicates. Order: venue →
 * artists → gig. For each entity the rules are tried in order and the first hit wins; every decision is
 * counted in {@link RunStats}. One gig is one transaction, so a bad gig rolls back alone.
 *
 * <p>Catalog provides the SQL primitives ({@link CatalogIngest});
 * this class only decides.
 */
@Service
public class EntityResolver {

    static final int VENUE_NEAR_METRES = 150;
    static final double VENUE_NEAR_SIMILARITY = 0.55;
    static final double VENUE_POSTCODE_SIMILARITY = 0.45;
    static final double ARTIST_SIMILARITY = 0.85;
    static final Duration GIG_WINDOW = Duration.ofHours(3);
    static final double GIG_TITLE_SIMILARITY = 0.6;
    /** When several listings point at one gig, the most trusted source's listing owns its fields. Lower wins. */
    static final List<String> SOURCE_PRIORITY = List.of("TICKETMASTER", "SKIDDLE");

    /** What happened to the gig. */
    public enum Outcome {
        CREATED,
        UPDATED,
        UNCHANGED,
        MERGED
    }

    private final CatalogIngest catalog;

    public EntityResolver(CatalogIngest catalog) {
        this.catalog = catalog;
    }

    @Transactional
    public Outcome ingest(SourceGig gig, RunStats stats, Instant seenAt) {
        UUID venueId = resolveVenue(gig.source(), gig.venue(), stats);
        List<UUID> artistIds = new ArrayList<>();
        for (SourceArtist artist : gig.artists()) {
            UUID artistId = resolveArtist(gig.source(), artist, venueId, stats);
            if (!artistIds.contains(artistId)) { // two source acts can resolve to one artist
                artistIds.add(artistId);
            }
        }
        GigDraft draft = new GigDraft(
                gig.title(),
                venueId,
                gig.startsAt(),
                gig.doorsAt(),
                gig.status(),
                gig.ticketUrl(),
                gig.priceMin(),
                gig.priceMax(),
                gig.currency(),
                gig.imageUrl(),
                contentHash(gig, venueId, artistIds),
                artistIds);
        return resolveGig(gig, draft, stats, seenAt);
    }

    // ── venue: external id → within 150 m and similar name → same postcode and similar name → create ──

    private UUID resolveVenue(String source, SourceVenue venue, RunStats stats) {
        Optional<UUID> known = catalog.venueByExternalId(source, venue.externalId());
        if (known.isPresent()) {
            stats.venues.matchedByExternalId++;
            return known.get();
        }
        // Fuzzy rules only bridge *sources*: a venue this source already knows under another id is a
        // different venue to it (Manchester Academy 1, 2 and 3 share a building and most of a name).
        Optional<UUID> fuzzy = Optional.empty();
        if (venue.lat() != null && venue.lng() != null) {
            fuzzy = catalog.venueNear(
                    venue.lat(), venue.lng(), VENUE_NEAR_METRES, venue.name(), VENUE_NEAR_SIMILARITY, source);
        }
        if (fuzzy.isEmpty() && venue.postcode() != null) {
            fuzzy = catalog.venueByPostcode(venue.postcode(), venue.name(), VENUE_POSTCODE_SIMILARITY, source);
        }
        UUID id;
        if (fuzzy.isPresent()) {
            stats.venues.matchedFuzzy++;
            id = fuzzy.get();
        } else {
            stats.venues.created++;
            id = catalog.createVenue(new VenueDraft(
                    venue.name(),
                    venue.address(),
                    venue.city(),
                    venue.postcode(),
                    venue.lat(),
                    venue.lng(),
                    venue.timezone()));
        }
        catalog.linkVenue(source, venue.externalId(), id); // next time rule 1 answers
        return id;
    }

    // ── artist: external id → MBID → exact normalised name → very similar name + shared genre/venue → create ──

    private UUID resolveArtist(String source, SourceArtist artist, UUID venueId, RunStats stats) {
        Optional<UUID> known = catalog.artistByExternalId(source, artist.externalId());
        if (known.isPresent()) {
            stats.artists.matchedByExternalId++;
            return known.get();
        }
        Map<Short, Float> genres = genreWeights(artist.genres());
        Optional<UUID> match = artist.mbid() == null ? Optional.empty() : catalog.artistByMbid(artist.mbid());
        if (match.isEmpty()) {
            match = catalog.artistByName(artist.name());
        }
        if (match.isEmpty()) {
            match = catalog.artistSimilar(artist.name(), ARTIST_SIMILARITY, genres.keySet(), venueId);
        }
        UUID id;
        if (match.isPresent()) {
            stats.artists.matchedFuzzy++;
            id = match.get();
            catalog.completeArtist(id, artist.mbid(), artist.imageUrl());
        } else {
            stats.artists.created++;
            id = catalog.createArtist(new ArtistDraft(artist.name(), artist.mbid(), artist.imageUrl(), genres));
        }
        catalog.linkArtist(source, artist.externalId(), id);
        return id;
    }

    /**
     * The source's own genre labels mapped through {@code genre_alias}, weights normalised to sum 1 and
     * the top three kept. This is the fallback genre source; enrichment replaces it when
     * Last.fm knows the artist.
     */
    Map<Short, Float> genreWeights(Collection<String> labels) {
        Map<String, CatalogIngest.GenreMatch> matches = catalog.genresForAliases(labels);
        Map<Short, Float> raw = new LinkedHashMap<>();
        for (String label : labels) {
            CatalogIngest.GenreMatch match = matches.get(label.toLowerCase(Locale.ROOT));
            if (match != null) {
                raw.merge(match.genreId(), match.weight(), Float::sum);
            }
        }
        return GenreWeights.topNormalised(raw, 3);
    }

    // ── gig: (source, external id) → same venue ± 3 h with same headliner or similar title (merge) → create ──

    private Outcome resolveGig(SourceGig gig, GigDraft draft, RunStats stats, Instant seenAt) {
        Optional<GigRef> known = catalog.gigBySource(gig.source(), gig.externalId());
        if (known.isPresent()) {
            stats.gigs.matchedByExternalId++;
            GigRef ref = known.get();
            catalog.linkGig(gig.source(), gig.externalId(), ref.id(), gig.url(), seenAt); // refresh last_seen_at
            if (Arrays.equals(ref.contentHash(), draft.contentHash())
                    || !ownsFields(gig.source(), gig.externalId(), ref)) {
                stats.unchanged++;
                return Outcome.UNCHANGED;
            }
            catalog.updateGig(ref.id(), draft);
            stats.updated++;
            return Outcome.UPDATED;
        }
        Optional<GigRef> same = catalog.gigAtVenueAround(
                draft.venueId(),
                draft.startsAt(),
                GIG_WINDOW,
                draft.artistIds().getFirst(),
                draft.title(),
                GIG_TITLE_SIMILARITY);
        if (same.isPresent()) {
            // Another listing already has this gig: record the extra provenance. The gig keeps its fields
            // unless the new listing is the one that owns them (see ownsFields); taking them over now,
            // rather than on the next run, is what makes a re-run find everything unchanged.
            stats.gigs.matchedFuzzy++;
            stats.merged++;
            GigRef existing = same.get();
            catalog.linkGig(gig.source(), gig.externalId(), existing.id(), gig.url(), seenAt);
            List<GigRef.Listing> listings = new ArrayList<>(existing.listings());
            listings.add(new GigRef.Listing(gig.source(), gig.externalId()));
            if (ownsFields(
                    gig.source(), gig.externalId(), new GigRef(existing.id(), existing.contentHash(), listings))) {
                catalog.updateGig(existing.id(), draft);
            }
            return Outcome.MERGED;
        }
        stats.gigs.created++;
        stats.created++;
        UUID id = catalog.createGig(draft);
        catalog.linkGig(gig.source(), gig.externalId(), id, gig.url(), seenAt);
        return Outcome.CREATED;
    }

    /**
     * One gig can have several listings: two sources, or one source listing a show twice ("VIP Package").
     * If each rewrote the gig, every run would flip its fields back and forth. So exactly one listing owns
     * them: the one from the most trusted source, and among those the smallest external id (any stable
     * rule would do; this one needs no extra column).
     */
    static boolean ownsFields(String source, String externalId, GigRef ref) {
        GigRef.Listing owner = ref.listings().stream()
                .min(Comparator.comparingInt((GigRef.Listing l) -> priority(l.source()))
                        .thenComparing(GigRef.Listing::externalId))
                .orElseThrow();
        return owner.source().equals(source) && owner.externalId().equals(externalId);
    }

    private static int priority(String source) {
        int index = SOURCE_PRIORITY.indexOf(source);
        return index < 0 ? SOURCE_PRIORITY.size() : index;
    }

    /** SHA-256 over every stored field, so "did anything change?" is one comparison. */
    static byte[] contentHash(SourceGig gig, UUID venueId, List<UUID> artistIds) {
        String canonical = String.join(
                "\u001f",
                gig.title(),
                venueId.toString(),
                gig.startsAt().toString(),
                String.valueOf(gig.doorsAt()),
                gig.status(),
                String.valueOf(gig.ticketUrl()),
                gig.priceMin() == null ? "" : gig.priceMin().toPlainString(),
                gig.priceMax() == null ? "" : gig.priceMax().toPlainString(),
                String.valueOf(gig.currency()),
                String.valueOf(gig.imageUrl()),
                artistIds.toString());
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
