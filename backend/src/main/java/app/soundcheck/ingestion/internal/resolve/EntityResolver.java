package app.soundcheck.ingestion.internal.resolve;

import app.soundcheck.catalog.ArtistDraft;
import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.GigCandidate;
import app.soundcheck.catalog.GigDraft;
import app.soundcheck.catalog.GigRef;
import app.soundcheck.catalog.VenueDraft;
import app.soundcheck.ingestion.internal.RunStats;
import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import app.soundcheck.ingestion.internal.source.TimePrecision;
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
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
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
    /**
     * setlist.fm places a venue only by its town: its venues sit at the town's centre, so "near"
     * means the same town and the name does the work, as for a fan-added venue (within 30 km, names
     * at least 0.9 similar once normalised).
     */
    static final Set<String> PLACED_BY_TOWN = Set.of("SETLISTFM");

    static final int TOWN_METRES = 30_000;
    static final double TOWN_SIMILARITY = 0.9;

    /** A primary listing its source has not shown for this long is considered gone. */
    static final Duration PRIMARY_STALE_AFTER = Duration.ofDays(3);

    private static final Pattern PACKAGE = Pattern.compile(
            "\\b(vip|premium|packages?|hospitality|upgrades?|platinum|meet\\s*(&|and|n)?\\s*greet|early entry|priority entry)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Where a title is cut into parts for {@link #plainTitle}: " - ", " – ", " — ", " | ", ": " and " : ". */
    private static final Pattern TITLE_SEPARATOR = Pattern.compile("\\s+[-–—|]\\s+|\\s*:\\s+");

    /** A bracketed aside, e.g. "(VIP Package)", dropped by {@link #plainTitle} when it names a package. */
    private static final Pattern BRACKETED = Pattern.compile("\\s*[(\\[][^)\\]]*[)\\]]");

    /**
     * When several listings point at one gig, a more trusted source's listing supplies its fields. Lower
     * wins: ticketing sources, then a venue's own site, then fans and setlist.fm.
     */
    static final List<String> SOURCE_PRIORITY = List.of("TICKETMASTER", "SKIDDLE", "VENUE", "USER", "SETLISTFM");

    /** What happened to the gig. */
    public enum Outcome {
        CREATED,
        UPDATED,
        UNCHANGED,
        MERGED
    }

    private final CatalogIngest catalog;
    private final MatchReviews reviews;

    public EntityResolver(CatalogIngest catalog, MatchReviews reviews) {
        this.catalog = catalog;
        this.reviews = reviews;
    }

    @Transactional
    public Outcome ingest(SourceGig gig, RunStats stats, Instant seenAt) {
        UUID venueId = resolveVenue(gig.source(), gig.venue(), stats);
        List<UUID> artistIds = new ArrayList<>();
        for (SourceArtist artist : actsOf(gig)) {
            UUID artistId = resolveArtist(gig.source(), artist, venueId, stats);
            if (!artistIds.contains(artistId)) { // two source acts can resolve to one artist
                artistIds.add(artistId);
            }
        }
        String title = storedTitle(gig);
        GigDraft draft = new GigDraft(
                title,
                venueId,
                gig.startsAt(),
                gig.doorsAt(),
                gig.status(),
                gig.ticketUrl(),
                gig.priceMin(),
                gig.priceMax(),
                gig.currency(),
                gig.imageUrl(),
                contentHash(gig, title, venueId, artistIds),
                artistIds);
        return resolveGig(gig, draft, stats, seenAt);
    }

    /**
     * The acts to resolve. Acts read out of a title (sources without act ids) were split on
     * {@code +} and commas, which breaks names like "Florence + The Machine": when the unsplit text is
     * one artist the catalog already knows (and a source vouches for), that artist is the act.
     */
    private List<SourceArtist> actsOf(SourceGig gig) {
        List<SourceArtist> acts = gig.artists();
        boolean fromTitle =
                acts.size() > 1 && acts.stream().allMatch(a -> a.externalId().isBlank());
        if (fromTitle) {
            String whole = TitleParser.parse(gig.title()).whole();
            // Only an artist something vouches for (MBID, a source's id) may undo the split: an artist
            // created from an earlier, wrong parse of the same title must not confirm itself.
            if (vouchedArtist(whole)) {
                return List.of(new SourceArtist("", whole, null, List.of(), null));
            }
        }
        if (!"VENUE".equals(gig.source())) {
            return acts;
        }
        List<SourceArtist> split = new ArrayList<>();
        for (SourceArtist act : acts) {
            split.addAll(splitPair(act));
        }
        return split;
    }

    /**
     * A venue site's "A & B": two acts when "A & B" is not itself an artist that
     * something vouches for and both halves are ("HED PE & COLD"), one act otherwise ("Jesse & Joy").
     * "A & the B" is always one act ("Echo & the Bunnymen", "Geraint Watkins & the Mosquitoes").
     */
    List<SourceArtist> splitPair(SourceArtist act) {
        String[] halves = act.name().split("\\s+&\\s+");
        if (!act.externalId().isBlank()
                || halves.length != 2
                || halves[0].isBlank()
                || halves[1].isBlank()
                || halves[1].strip().regionMatches(true, 0, "the ", 0, 4)
                || vouchedArtist(act.name())
                || !vouchedArtist(halves[0])
                || !vouchedArtist(halves[1])) {
            return List.of(act);
        }
        return List.of(
                new SourceArtist("", halves[0].strip(), null, List.of(), null),
                new SourceArtist("", halves[1].strip(), null, List.of(), null));
    }

    /** An artist the catalog knows by this exact (normalised) name, and that a source or MBID vouches for. */
    private boolean vouchedArtist(String name) {
        Optional<UUID> known = catalog.artistByName(name.strip(), null);
        return known.isPresent() && catalog.artistIsVouchedFor(known.get());
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
            boolean byTown = PLACED_BY_TOWN.contains(source);
            fuzzy = catalog.venueNear(
                    venue.lat(),
                    venue.lng(),
                    byTown ? TOWN_METRES : VENUE_NEAR_METRES,
                    venue.name(),
                    byTown ? TOWN_SIMILARITY : VENUE_NEAR_SIMILARITY,
                    source);
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
        // A blank external id means the source has no ids for acts (a venue site): names only.
        boolean hasId = !artist.externalId().isBlank();
        Optional<UUID> known = hasId ? catalog.artistByExternalId(source, artist.externalId()) : Optional.empty();
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
        if (hasId) {
            catalog.linkArtist(source, artist.externalId(), id);
        }
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

    // ── gig: this listing's id → same venue and night, judged by GigMatcher (merge / log / create) → create ──

    private Outcome resolveGig(SourceGig gig, GigDraft draft, RunStats stats, Instant seenAt) {
        Optional<GigRef> known = catalog.gigBySource(gig.source(), gig.externalId());
        if (known.isPresent()) {
            stats.gigs.matchedByExternalId++;
            GigRef ref = known.get();
            catalog.linkGig(gig.source(), gig.externalId(), ref.id(), gig.url(), seenAt, false, isPackage(gig.title()));
            // (refreshes last_seen_at and the package flag)
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
        // Same venue, same night: the matcher decides which candidate, if any, is this show.
        Instant nightStart = GigMatcher.night(gig.startsAt())
                .atTime(6, 0)
                .atZone(GigMatcher.UK)
                .toInstant();
        GigMatcher.Side incoming = new GigMatcher.Side(
                gig.source(),
                gig.startsAt(),
                gig.timePrecision(),
                gig.title(),
                draft.artistIds(),
                gig.artists().stream().map(SourceArtist::name).toList(),
                isPackage(gig.title()));
        GigCandidate best = null;
        GigMatcher.Decision bestDecision = null;
        List<Map.Entry<GigCandidate, GigMatcher.Decision>> almost = new ArrayList<>();
        List<Map.Entry<GigCandidate, GigMatcher.Decision>> oneActMatches = new ArrayList<>();
        for (GigCandidate candidate :
                catalog.gigsAtVenueBetween(draft.venueId(), nightStart, nightStart.plus(Duration.ofDays(1)))) {
            GigRef ref = candidate.gig();
            GigMatcher.Side existing = new GigMatcher.Side(
                    ref.primary().source(),
                    ref.startsAt(),
                    precisionOf(ref.primary().source()),
                    ref.title(),
                    candidate.artistIds(),
                    candidate.artistNames(),
                    ref.primary().packageListing());
            GigMatcher.Decision decision = GigMatcher.decide(
                    incoming,
                    existing,
                    ref.listings().stream().map(GigRef.Listing::source).toList(),
                    candidate.venueId().equals(draft.venueId()));
            if (decision.reason().equals(GigMatcher.ONE_ACT_OF_THE_NIGHT)) {
                oneActMatches.add(Map.entry(candidate, decision));
                continue; // decided below, once every candidate has been seen
            }
            switch (decision.verdict()) {
                case SAME -> {
                    if (best == null || closer(gig, ref, best.gig())) {
                        best = candidate;
                        bestDecision = decision;
                    }
                }
                case MAYBE -> almost.add(Map.entry(candidate, decision));
                case DIFFERENT -> {}
            }
        }
        // A setlist that shares no act with any gig of the night joins the night's only gig at the venue; with
        // two or more there it cannot tell which, so it becomes a gig of its own and the pairs go to review.
        if (best == null && oneActMatches.size() == 1) {
            best = oneActMatches.getFirst().getKey();
            bestDecision = oneActMatches.getFirst().getValue();
        } else {
            for (Map.Entry<GigCandidate, GigMatcher.Decision> pair : oneActMatches) {
                GigMatcher.Decision d = pair.getValue();
                almost.add(Map.entry(
                        pair.getKey(),
                        new GigMatcher.Decision(
                                GigMatcher.Verdict.MAYBE,
                                "one act's setlist, more than one gig that night",
                                d.titleSimilarity(),
                                d.actSimilarity())));
            }
        }
        if (best != null && bestDecision != null) {
            GigRef candidate = best.gig();
            stats.gigs.matchedFuzzy++;
            stats.merged++;
            catalog.linkGig(
                    gig.source(), gig.externalId(), candidate.id(), gig.url(), seenAt, false, isPackage(gig.title()));
            if (takesOver(gig, candidate)) {
                catalog.makePrimary(candidate.id(), gig.source(), gig.externalId());
                catalog.updateGig(candidate.id(), draft);
            } else if (catalog.fillGig(candidate.id(), draft)) {
                // Not trusted enough to rewrite the gig, but it can say what the gig does not (support acts).
                stats.filled++;
            }
            return Outcome.MERGED;
        }
        stats.gigs.created++;
        stats.created++;
        UUID id = catalog.createGig(draft);
        catalog.linkGig(gig.source(), gig.externalId(), id, gig.url(), seenAt, true, isPackage(gig.title()));
        for (Map.Entry<GigCandidate, GigMatcher.Decision> pair : almost) {
            // Close but not convincing: kept apart, and asked about once in the console.
            stats.flaggedForReview++;
            reviews.record(
                    gig.source(),
                    gig.externalId(),
                    id,
                    pair.getKey().gig().id(),
                    gig.title(),
                    pair.getKey().gig().title(),
                    pair.getValue());
        }
        return Outcome.CREATED;
    }

    /** How far a gig's start can be trusted, from the source of its primary listing. */
    static TimePrecision precisionOf(String source) {
        return switch (source) {
            case "VENUE" -> TimePrecision.DOORS;
            case "USER", "SETLISTFM" -> TimePrecision.DATE_ONLY;
            default -> TimePrecision.EXACT;
        };
    }

    /** Of two matching gigs, the one starting nearer the listing. */
    private static boolean closer(SourceGig gig, GigRef a, GigRef b) {
        return Duration.between(gig.startsAt(), a.startsAt())
                        .abs()
                        .compareTo(
                                Duration.between(gig.startsAt(), b.startsAt()).abs())
                < 0;
    }

    /**
     * Should the incoming listing become the gig's primary one (and so supply its fields)? Yes when it comes
     * from a more trusted source than the current primary, or when the gig currently follows a package
     * listing and this is the plain listing of the same source.
     */
    static boolean takesOver(SourceGig incoming, GigRef existing) {
        GigRef.Listing primary = existing.primary();
        if (priority(incoming.source()) != priority(primary.source())) {
            return priority(incoming.source()) < priority(primary.source());
        }
        return primary.packageListing() && !isPackage(incoming.title());
    }

    /** Titles of hospitality and upgrade listings that duplicate a show. */
    static boolean isPackage(String title) {
        return PACKAGE.matcher(title).find();
    }

    /**
     * The title a gig is stored under. A Ticketmaster package listing ("Westlife: Venue Premium Tickets")
     * is often the only listing of a show in the data; its gig keeps the show's own title ("Westlife"), and
     * the listing keeps its package flag, so the plain listing still merges and takes over when it appears.
     * Venue sites' titles are left alone: there a word like "Hospitality" is as
     * likely to be a band.
     */
    static String storedTitle(SourceGig gig) {
        return "TICKETMASTER".equals(gig.source()) && isPackage(gig.title()) ? plainTitle(gig.title()) : gig.title();
    }

    /**
     * {@code title} without the parts that name a package: the title is cut at " - ", " – ", " | " and
     * ": ", and every part (or bracketed aside) that matches {@link #isPackage} is dropped; the rest keeps
     * its own separators. A title that is nothing but a package ("VIP Package") stays as it is.
     */
    static String plainTitle(String title) {
        String text = title.strip().replaceAll("\\s{2,}", " ");
        text = BRACKETED.matcher(text).replaceAll(m -> isPackage(m.group()) ? "" : Matcher.quoteReplacement(m.group()));
        var parts = new ArrayList<String>();
        var separators = new ArrayList<String>();
        var cut = TITLE_SEPARATOR.matcher(text);
        int from = 0;
        String separator = "";
        while (cut.find()) {
            parts.add(text.substring(from, cut.start()));
            separators.add(separator);
            separator = cut.group();
            from = cut.end();
        }
        parts.add(text.substring(from));
        separators.add(separator);
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i).strip();
            if (part.isEmpty() || isPackage(part)) {
                continue;
            }
            if (!kept.isEmpty()) {
                kept.append(separators.get(i).strip().equals(":") ? ": " : separators.get(i));
            }
            kept.append(part);
        }
        return kept.isEmpty() ? title.strip() : kept.toString();
    }

    private static int priority(String source) {
        int index = SOURCE_PRIORITY.indexOf(source);
        return index < 0 ? SOURCE_PRIORITY.size() : index;
    }

    /** SHA-256 over every stored field, so "did anything change?" is one comparison. */
    static byte[] contentHash(SourceGig gig, String title, UUID venueId, List<UUID> artistIds) {
        String canonical = String.join(
                "\u001f",
                title,
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
