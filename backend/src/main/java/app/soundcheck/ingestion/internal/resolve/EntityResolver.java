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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns one {@link SourceGig} into catalog rows without creating duplicates. Order: venue →
 * artists → gig. For each entity the rules are tried in order and the first hit wins; every decision is
 * counted in {@link RunStats}. One gig is one transaction, so a bad gig rolls back alone.
 *
 * <p>Catalog provides the SQL primitives ({@link CatalogIngest});
 * this class only decides. Each gig has exactly one <em>primary</em> listing, the only one whose changes
 * are written to the gig; other listings of the same show only add provenance.
 */
@Service
public class EntityResolver {

    static final int VENUE_NEAR_METRES = 150;
    static final double VENUE_NEAR_SIMILARITY = 0.55;
    static final double VENUE_POSTCODE_SIMILARITY = 0.45;
    static final double ARTIST_SIMILARITY = 0.85;
    static final Duration GIG_WINDOW = Duration.ofHours(3);
    static final double GIG_TITLE_SIMILARITY = 0.6;
    /** A primary listing its source has not shown for this long is considered gone. */
    static final Duration PRIMARY_STALE_AFTER = Duration.ofDays(3);

    private static final Pattern PACKAGE = Pattern.compile(
            "\\b(vip|premium|packages?|hospitality|upgrades?|platinum|meet\\s*(&|and|n)?\\s*greet|early entry|priority entry)\\b",
            Pattern.CASE_INSENSITIVE);

    /** When several listings point at one gig, a more trusted source's listing supplies its fields. Lower wins. */
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
            // A venue first listed without coordinates gets them as soon as a source supplies them.
            catalog.completeVenue(known.get(), venue.lat(), venue.lng());
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
            match = catalog.artistByName(artist.name(), artist.mbid());
        }
        if (match.isEmpty()) {
            match = catalog.artistSimilar(artist.name(), ARTIST_SIMILARITY, genres.keySet(), venueId, artist.mbid());
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

    // ── gig: this listing's id → same venue ± 3 h and same headliner or similar title (merge) → create ──

    private Outcome resolveGig(SourceGig gig, GigDraft draft, RunStats stats, Instant seenAt) {
        Optional<GigRef> known = catalog.gigBySource(gig.source(), gig.externalId());
        if (known.isPresent()) {
            stats.gigs.matchedByExternalId++;
            GigRef ref = known.get();
            catalog.linkGig(gig.source(), gig.externalId(), ref.id(), gig.url(), seenAt, false); // refresh last_seen_at
            GigRef.Listing primary = ref.primary();
            boolean mine = primary.source().equals(gig.source())
                    && primary.externalId().equals(gig.externalId());
            if (!mine && primary.lastSeenAt().isBefore(seenAt.minus(PRIMARY_STALE_AFTER))) {
                // The primary listing has disappeared from its source: this one takes over, so the gig
                // keeps following a listing that is still alive (a cancellation would otherwise be missed).
                catalog.makePrimary(ref.id(), gig.source(), gig.externalId());
                mine = true;
            }
            if (!mine || Arrays.equals(ref.contentHash(), draft.contentHash())) {
                stats.unchanged++;
                return Outcome.UNCHANGED;
            }
            catalog.updateGig(ref.id(), draft);
            stats.updated++;
            return Outcome.UPDATED;
        }
        for (GigRef candidate : catalog.gigsAtVenueAround(
                draft.venueId(),
                draft.startsAt(),
                GIG_WINDOW,
                draft.artistIds().getFirst(),
                draft.title(),
                GIG_TITLE_SIMILARITY)) {
            if (!sameShow(gig, candidate)) {
                continue;
            }
            stats.gigs.matchedFuzzy++;
            stats.merged++;
            catalog.linkGig(gig.source(), gig.externalId(), candidate.id(), gig.url(), seenAt, false);
            if (takesOver(gig, candidate)) {
                catalog.makePrimary(candidate.id(), gig.source(), gig.externalId());
                catalog.updateGig(candidate.id(), draft);
            }
            return Outcome.MERGED;
        }
        stats.gigs.created++;
        stats.created++;
        UUID id = catalog.createGig(draft);
        catalog.linkGig(gig.source(), gig.externalId(), id, gig.url(), seenAt, true);
        return Outcome.CREATED;
    }

    /**
     * Is the incoming listing the same show as this candidate (same venue, within ± 3 h, same headliner or
     * a similar title)? Across sources, yes: two sources rarely agree on the minute, and one may give the
     * doors time. Within one source the source's own ids mean something: an 18:00 and a 20:30 listing of the
     * same act are an early and a late show. They are one show only when they start at the same instant,
     * or when one of the two is a package listing ("… - VIP Package", "Venue Premium Tickets"), which
     * sources often date at the doors time.
     */
    static boolean sameShow(SourceGig incoming, GigRef candidate) {
        if (!candidate.listedBy(incoming.source())) {
            return true;
        }
        return candidate.startsAt().equals(incoming.startsAt())
                || isPackage(incoming.title())
                || isPackage(candidate.title());
    }

    /**
     * Should the incoming listing become the gig's primary one (and so supply its fields)? Yes when it comes
     * from a more trusted source than the current primary, or when the gig currently carries a package
     * listing's title and this is the plain listing of the same source.
     */
    static boolean takesOver(SourceGig incoming, GigRef existing) {
        GigRef.Listing primary = existing.primary();
        if (priority(incoming.source()) != priority(primary.source())) {
            return priority(incoming.source()) < priority(primary.source());
        }
        return isPackage(existing.title()) && !isPackage(incoming.title());
    }

    /** Titles of hospitality and upgrade listings that duplicate a show. */
    static boolean isPackage(String title) {
        return PACKAGE.matcher(title).find();
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
