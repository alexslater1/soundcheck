package app.soundcheck.catalog.internal;

import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST_EXTERNAL_ID;
import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST_GENRE;
import static app.soundcheck.shared.jooq.catalog.Tables.GENRE_ALIAS;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_SOURCE;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE_EXTERNAL_ID;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE_SIBLING;

import app.soundcheck.catalog.ArtistDraft;
import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.GigCandidate;
import app.soundcheck.catalog.GigDraft;
import app.soundcheck.catalog.GigRef;
import app.soundcheck.catalog.ListingOverlap;
import app.soundcheck.catalog.NameNormaliser;
import app.soundcheck.catalog.VenueDraft;
import app.soundcheck.catalog.VenueNote;
import app.soundcheck.catalog.VenueToLocate;
import app.soundcheck.shared.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * jOOQ implementation of {@link CatalogIngest}. The matching primitives lean on PostgreSQL: PostGIS
 * {@code ST_DWithin} on geography for "within N metres" and pg_trgm {@code similarity()} for fuzzy names.
 */
@Repository
@Transactional
class JooqCatalogIngest implements CatalogIngest {

    /** A gig its only site stopped listing (V045). */
    static final String UNLISTED = "UNLISTED";

    private final DSLContext dsl;
    private final Clock clock;

    JooqCatalogIngest(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    // ── venues ──────────────────────────────────────────────────────────────

    @Override
    public Optional<UUID> venueByExternalId(String source, String externalId) {
        return dsl.select(VENUE_EXTERNAL_ID.VENUE_ID)
                .from(VENUE_EXTERNAL_ID)
                .where(VENUE_EXTERNAL_ID.SOURCE.eq(source))
                .and(VENUE_EXTERNAL_ID.EXTERNAL_ID.eq(externalId))
                .fetchOptional(VENUE_EXTERNAL_ID.VENUE_ID);
    }

    @Override
    public Optional<UUID> venueNear(
            double lat, double lng, int metres, String name, double minSimilarity, String notKnownTo) {
        Field<Double> similarity = similarity(VENUE.NAME_NORM, NameNormaliser.normalise(name));
        // Expected index: venue_location_gix (GiST) for ST_DWithin; the handful of venues it returns are
        // then compared by name.
        return dsl.select(VENUE.ID)
                .from(VENUE)
                .where(DSL.condition("ST_DWithin({0}, {1}, {2})", VENUE.LOCATION, point(lat, lng), DSL.val(metres)))
                .and(similarity.ge(minSimilarity))
                .and(notKnownTo(notKnownTo))
                .orderBy(similarity.desc(), VENUE.ID)
                .limit(1)
                .fetchOptional(VENUE.ID);
    }

    @Override
    public Optional<UUID> venueByPostcode(String postcode, String name, double minSimilarity, String notKnownTo) {
        Field<Double> similarity = similarity(VENUE.NAME_NORM, NameNormaliser.normalise(name));
        // No index: catalog.venue holds a few thousand rows; revisit if EXPLAIN says otherwise.
        return dsl.select(VENUE.ID)
                .from(VENUE)
                .where(DSL.upper(DSL.replace(VENUE.POSTCODE, " ", "")).eq(compactPostcode(postcode)))
                .and(similarity.ge(minSimilarity))
                .and(notKnownTo(notKnownTo))
                .orderBy(similarity.desc(), VENUE.ID)
                .limit(1)
                .fetchOptional(VENUE.ID);
    }

    private static Condition notKnownTo(String source) {
        return DSL.notExists(DSL.selectOne()
                .from(VENUE_EXTERNAL_ID)
                .where(VENUE_EXTERNAL_ID.VENUE_ID.eq(VENUE.ID))
                .and(VENUE_EXTERNAL_ID.SOURCE.eq(source)));
    }

    @Override
    public UUID createVenue(VenueDraft venue) {
        UUID id = Ids.newId();
        OffsetDateTime now = now();
        var insert = dsl.insertInto(VENUE)
                .set(VENUE.ID, id)
                .set(VENUE.NAME, venue.name())
                .set(VENUE.NAME_NORM, NameNormaliser.normalise(venue.name()))
                .set(VENUE.ADDRESS, venue.address())
                .set(VENUE.CITY, venue.city())
                .set(VENUE.POSTCODE, venue.postcode())
                .set(VENUE.TIMEZONE, venue.timezone())
                .set(VENUE.CREATED_AT, now)
                .set(VENUE.UPDATED_AT, now);
        if (venue.lat() != null && venue.lng() != null) {
            // Map form avoids the set(Field, Object) / set(Field, Field) overload ambiguity on geography.
            insert = insert.set(Map.of(VENUE.LOCATION, point(venue.lat(), venue.lng())));
        }
        insert.execute();
        return id;
    }

    @Override
    public void linkVenue(String source, String externalId, UUID venueId) {
        dsl.insertInto(VENUE_EXTERNAL_ID)
                .set(VENUE_EXTERNAL_ID.SOURCE, source)
                .set(VENUE_EXTERNAL_ID.EXTERNAL_ID, externalId)
                .set(VENUE_EXTERNAL_ID.VENUE_ID, venueId)
                .onConflictDoNothing()
                .execute();
    }

    @Override
    public void linkSiblings(UUID venueId, UUID siblingId) {
        if (venueId.equals(siblingId)) {
            return;
        }
        // The whole group: both venues and everything already linked to either, every pair both ways.
        Set<UUID> group = new LinkedHashSet<>(List.of(venueId, siblingId));
        group.addAll(dsl.select(VENUE_SIBLING.SIBLING_ID)
                .from(VENUE_SIBLING)
                .where(VENUE_SIBLING.VENUE_ID.in(venueId, siblingId))
                .fetch(VENUE_SIBLING.SIBLING_ID));
        for (UUID a : group) {
            for (UUID b : group) {
                if (!a.equals(b)) {
                    dsl.insertInto(VENUE_SIBLING)
                            .set(VENUE_SIBLING.VENUE_ID, a)
                            .set(VENUE_SIBLING.SIBLING_ID, b)
                            .onConflictDoNothing()
                            .execute();
                }
            }
        }
    }

    // ── artists ─────────────────────────────────────────────────────────────

    @Override
    public Optional<UUID> artistByExternalId(String source, String externalId) {
        return dsl.select(ARTIST_EXTERNAL_ID.ARTIST_ID)
                .from(ARTIST_EXTERNAL_ID)
                .where(ARTIST_EXTERNAL_ID.SOURCE.eq(source))
                .and(ARTIST_EXTERNAL_ID.EXTERNAL_ID.eq(externalId))
                .fetchOptional(ARTIST_EXTERNAL_ID.ARTIST_ID);
    }

    @Override
    public Optional<UUID> artistByMbid(UUID mbid) {
        return dsl.select(ARTIST.ID).from(ARTIST).where(ARTIST.MBID.eq(mbid)).fetchOptional(ARTIST.ID);
    }

    @Override
    public Optional<UUID> artistByName(String name, @Nullable UUID mbid) {
        // Expected index: artist_name_norm_trgm (pg_trgm GIN supports equality). Oldest first so the
        // answer is stable if two artists share a normalised name.
        return dsl.select(ARTIST.ID)
                .from(ARTIST)
                .where(ARTIST.NAME_NORM.eq(NameNormaliser.normalise(name)))
                .and(sameActAs(mbid))
                .orderBy(ARTIST.CREATED_AT, ARTIST.ID)
                .limit(1)
                .fetchOptional(ARTIST.ID);
    }

    @Override
    public Optional<UUID> artistSimilar(
            String name, double minSimilarity, Collection<Short> genreIds, UUID venueId, @Nullable UUID mbid) {
        String norm = NameNormaliser.normalise(name);
        Field<Double> similarity = similarity(ARTIST.NAME_NORM, norm);
        Condition sharesGenre = genreIds.isEmpty()
                ? DSL.falseCondition()
                : DSL.exists(DSL.selectOne()
                        .from(ARTIST_GENRE)
                        .where(ARTIST_GENRE.ARTIST_ID.eq(ARTIST.ID))
                        .and(ARTIST_GENRE.GENRE_ID.in(genreIds)));
        Condition playedVenue = DSL.exists(DSL.selectOne()
                .from(GIG_ARTIST)
                .join(GIG)
                .on(GIG.ID.eq(GIG_ARTIST.GIG_ID))
                .where(GIG_ARTIST.ARTIST_ID.eq(ARTIST.ID))
                .and(GIG.VENUE_ID.eq(venueId)));
        // Expected index: artist_name_norm_trgm — the % operator (similarity > 0.3) is index-assisted and
        // narrows the candidates before the exact similarity() threshold is applied.
        return dsl.select(ARTIST.ID)
                .from(ARTIST)
                .where(DSL.condition("{0} % {1}", ARTIST.NAME_NORM, DSL.val(norm)))
                .and(similarity.ge(minSimilarity))
                .and(sharesGenre.or(playedVenue))
                .and(sameActAs(mbid))
                .orderBy(similarity.desc(), ARTIST.ID)
                .limit(1)
                .fetchOptional(ARTIST.ID);
    }

    /** Two MusicBrainz ids that differ mean two different acts, however alike the names are. */
    private static Condition sameActAs(@Nullable UUID mbid) {
        return mbid == null ? DSL.noCondition() : ARTIST.MBID.isNull().or(ARTIST.MBID.eq(mbid));
    }

    @Override
    public UUID createArtist(ArtistDraft artist) {
        UUID id = Ids.newId();
        OffsetDateTime now = now();
        dsl.insertInto(ARTIST)
                .set(ARTIST.ID, id)
                .set(ARTIST.NAME, artist.name())
                .set(ARTIST.NAME_NORM, NameNormaliser.normalise(artist.name()))
                .set(ARTIST.MBID, artist.mbid())
                .set(ARTIST.IMAGE_URL, artist.imageUrl())
                .set(ARTIST.CREATED_AT, now)
                .set(ARTIST.UPDATED_AT, now)
                .execute();
        artist.genreWeights().forEach((genreId, weight) -> dsl.insertInto(ARTIST_GENRE)
                .set(ARTIST_GENRE.ARTIST_ID, id)
                .set(ARTIST_GENRE.GENRE_ID, genreId)
                .set(ARTIST_GENRE.WEIGHT, weight)
                .execute());
        return id;
    }

    @Override
    public boolean artistIsVouchedFor(UUID artistId) {
        return dsl.fetchExists(ARTIST, ARTIST.ID.eq(artistId).and(ARTIST.MBID.isNotNull()))
                || dsl.fetchExists(ARTIST_EXTERNAL_ID, ARTIST_EXTERNAL_ID.ARTIST_ID.eq(artistId));
    }

    @Override
    public void linkArtist(String source, String externalId, UUID artistId) {
        dsl.insertInto(ARTIST_EXTERNAL_ID)
                .set(ARTIST_EXTERNAL_ID.SOURCE, source)
                .set(ARTIST_EXTERNAL_ID.EXTERNAL_ID, externalId)
                .set(ARTIST_EXTERNAL_ID.ARTIST_ID, artistId)
                .onConflictDoNothing()
                .execute();
    }

    @Override
    public void completeArtist(UUID artistId, @Nullable UUID mbid, @Nullable String imageUrl) {
        if (mbid != null) {
            // Only when no artist holds this MBID yet: the column is unique.
            dsl.update(ARTIST)
                    .set(ARTIST.MBID, mbid)
                    .set(ARTIST.UPDATED_AT, now())
                    .where(ARTIST.ID.eq(artistId))
                    .and(ARTIST.MBID.isNull())
                    .andNotExists(DSL.selectOne()
                            .from(ARTIST.as("other"))
                            .where(DSL.field(DSL.name("other", "mbid"), UUID.class)
                                    .eq(mbid)))
                    .execute();
        }
        if (imageUrl != null) {
            dsl.update(ARTIST)
                    .set(ARTIST.IMAGE_URL, imageUrl)
                    .set(ARTIST.UPDATED_AT, now())
                    .where(ARTIST.ID.eq(artistId))
                    .and(ARTIST.IMAGE_URL.isNull())
                    .execute();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArtistToEnrich> artistsToEnrich(Instant staleBefore, int limit) {
        // Runs once per ingestion run over catalog.artist (tens of thousands of rows at most): no index.
        return dsl.select(ARTIST.ID, ARTIST.NAME, ARTIST.MBID)
                .from(ARTIST)
                .where(ARTIST.ENRICHED_AT.isNull().or(ARTIST.ENRICHED_AT.lt(at(staleBefore))))
                .orderBy(ARTIST.ENRICHED_AT.asc().nullsFirst(), ARTIST.CREATED_AT, ARTIST.ID)
                .limit(limit)
                .fetch(r -> new ArtistToEnrich(r.value1(), r.value2(), r.value3()));
    }

    @Override
    public void replaceArtistGenres(UUID artistId, Map<Short, Float> genreWeights) {
        if (genreWeights.isEmpty()) {
            return;
        }
        dsl.deleteFrom(ARTIST_GENRE).where(ARTIST_GENRE.ARTIST_ID.eq(artistId)).execute();
        genreWeights.forEach((genreId, weight) -> dsl.insertInto(ARTIST_GENRE)
                .set(ARTIST_GENRE.ARTIST_ID, artistId)
                .set(ARTIST_GENRE.GENRE_ID, genreId)
                .set(ARTIST_GENRE.WEIGHT, weight)
                .execute());
    }

    @Override
    public void markArtistEnriched(UUID artistId, Instant at) {
        dsl.update(ARTIST)
                .set(ARTIST.ENRICHED_AT, at(at))
                .set(ARTIST.UPDATED_AT, at(at))
                .where(ARTIST.ID.eq(artistId))
                .execute();
    }

    @Override
    @Transactional(readOnly = true)
    public int countWithGenres(Collection<UUID> artistIds) {
        if (artistIds.isEmpty()) {
            return 0;
        }
        return dsl.fetchCount(DSL.selectDistinct(ARTIST_GENRE.ARTIST_ID)
                .from(ARTIST_GENRE)
                .where(ARTIST_GENRE.ARTIST_ID.in(artistIds)));
    }

    @Override
    public Map<String, GenreMatch> genresForAliases(Collection<String> aliases) {
        Map<String, GenreMatch> matches = new HashMap<>();
        if (aliases.isEmpty()) {
            return matches;
        }
        // genre_alias.alias is citext, but a bound varchar parameter makes PostgreSQL compare as text
        // (case-sensitively), so both sides are lower-cased explicitly. ~130 rows: no index needed.
        List<String> lowered =
                aliases.stream().map(alias -> alias.toLowerCase(Locale.ROOT)).toList();
        Field<String> alias = DSL.lower(GENRE_ALIAS.ALIAS.cast(String.class));
        dsl.select(alias, GENRE_ALIAS.GENRE_ID, GENRE_ALIAS.WEIGHT)
                .from(GENRE_ALIAS)
                .where(alias.in(lowered))
                .forEach(r -> matches.put(r.value1(), new GenreMatch(r.value2(), r.value3())));
        return matches;
    }

    // ── gigs ────────────────────────────────────────────────────────────────

    @Override
    public Optional<GigRef> gigBySource(String source, String externalId) {
        return dsl.select(GIG_SOURCE.GIG_ID)
                .from(GIG_SOURCE)
                .where(GIG_SOURCE.SOURCE.eq(source))
                .and(GIG_SOURCE.EXTERNAL_ID.eq(externalId))
                .fetchOptional(GIG_SOURCE.GIG_ID)
                .map(this::ref);
    }

    @Override
    public List<GigRef> gigsAtVenueAround(
            UUID venueId,
            Instant startsAt,
            Duration window,
            UUID headlinerId,
            String title,
            double minTitleSimilarity) {
        Condition sameHeadliner = DSL.exists(DSL.selectOne()
                .from(GIG_ARTIST)
                .where(GIG_ARTIST.GIG_ID.eq(GIG.ID))
                .and(GIG_ARTIST.BILLING_ORDER.eq((short) 0))
                .and(GIG_ARTIST.ARTIST_ID.eq(headlinerId)));
        Field<Double> titleSimilarity = DSL.field(
                "similarity(lower({0}), {1})", Double.class, GIG.TITLE, DSL.val(title.toLowerCase(Locale.ROOT)));
        // Expected index: gig_venue (venue_id, starts_at) — one venue, a six-hour range.
        return dsl
                .select(GIG.ID)
                .from(GIG)
                .where(GIG.VENUE_ID.eq(venueId))
                .and(GIG.STARTS_AT.between(at(startsAt.minus(window)), at(startsAt.plus(window))))
                .and(sameHeadliner.or(titleSimilarity.ge(minTitleSimilarity)))
                .orderBy(
                        DSL.field(
                                "abs(extract(epoch from ({0} - {1})))",
                                Double.class, GIG.STARTS_AT, DSL.val(at(startsAt))),
                        GIG.ID)
                .limit(5)
                .fetch(GIG.ID)
                .stream()
                .map(this::ref)
                .toList();
    }

    @Override
    public List<GigCandidate> gigsAtVenueBetween(UUID venueId, Instant from, Instant to) {
        Map<UUID, UUID> venueOf = new LinkedHashMap<>();
        dsl.select(GIG.ID, GIG.VENUE_ID)
                .from(GIG)
                .where(GIG.VENUE_ID
                        .eq(venueId)
                        .or(GIG.VENUE_ID.in(DSL.select(VENUE_SIBLING.SIBLING_ID)
                                .from(VENUE_SIBLING)
                                .where(VENUE_SIBLING.VENUE_ID.eq(venueId)))))
                .and(GIG.STARTS_AT.ge(at(from)))
                .and(GIG.STARTS_AT.lt(at(to)))
                .orderBy(GIG.STARTS_AT, GIG.ID)
                .limit(20)
                .forEach(r -> venueOf.put(r.value1(), r.value2()));
        List<UUID> ids = List.copyOf(venueOf.keySet());
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<UUID>> artistIds = new java.util.HashMap<>();
        Map<UUID, List<String>> artistNames = new java.util.HashMap<>();
        dsl.select(GIG_ARTIST.GIG_ID, ARTIST.ID, ARTIST.NAME)
                .from(GIG_ARTIST)
                .join(ARTIST)
                .on(ARTIST.ID.eq(GIG_ARTIST.ARTIST_ID))
                .where(GIG_ARTIST.GIG_ID.in(ids))
                .orderBy(GIG_ARTIST.GIG_ID, GIG_ARTIST.BILLING_ORDER)
                .forEach(r -> {
                    artistIds
                            .computeIfAbsent(r.value1(), k -> new java.util.ArrayList<>())
                            .add(r.value2());
                    artistNames
                            .computeIfAbsent(r.value1(), k -> new java.util.ArrayList<>())
                            .add(r.value3());
                });
        return ids.stream()
                .map(id -> new GigCandidate(
                        ref(id),
                        venueOf.get(id),
                        artistIds.getOrDefault(id, List.of()),
                        artistNames.getOrDefault(id, List.of())))
                .toList();
    }

    @Override
    public List<ListingOverlap> listingsAlsoListedBy(String source, String other, Instant from, Instant to) {
        var otherListing = GIG_SOURCE.as("other_listing");
        Field<Boolean> alsoListed = DSL.field(DSL.exists(DSL.selectOne()
                .from(otherListing)
                .where(otherListing.GIG_ID.eq(GIG_SOURCE.GIG_ID))
                .and(otherListing.SOURCE.eq(other))));
        return dsl.select(GIG_SOURCE.EXTERNAL_ID, GIG.ID, GIG.TITLE, GIG.STARTS_AT, alsoListed)
                .from(GIG_SOURCE)
                .join(GIG)
                .on(GIG.ID.eq(GIG_SOURCE.GIG_ID))
                .where(GIG_SOURCE.SOURCE.eq(source))
                .and(GIG.STARTS_AT.ge(at(from)))
                .and(GIG.STARTS_AT.lt(at(to)))
                .and(GIG.STATUS.ne("CANCELLED"))
                .orderBy(GIG.STARTS_AT, GIG.ID)
                .fetch(r -> new ListingOverlap(
                        r.value1(), r.value2(), r.value3(), r.value4().toInstant(), r.value5()));
    }

    @Override
    public boolean fillGig(UUID gigId, GigDraft from) {
        int fields = dsl.update(GIG)
                .set(
                        GIG.DOORS_AT,
                        DSL.coalesce(GIG.DOORS_AT, DSL.val(from.doorsAt() == null ? null : at(from.doorsAt()))))
                .set(GIG.TICKET_URL, DSL.coalesce(GIG.TICKET_URL, DSL.val(from.ticketUrl())))
                .set(GIG.PRICE_MIN, DSL.coalesce(GIG.PRICE_MIN, DSL.val(from.priceMin())))
                .set(GIG.PRICE_MAX, DSL.coalesce(GIG.PRICE_MAX, DSL.val(from.priceMax())))
                .set(GIG.CURRENCY, DSL.coalesce(GIG.CURRENCY, DSL.val(from.currency())))
                .set(GIG.IMAGE_URL, DSL.coalesce(GIG.IMAGE_URL, DSL.val(from.imageUrl())))
                .set(GIG.UPDATED_AT, now())
                .where(GIG.ID.eq(gigId))
                .and(GIG.DOORS_AT
                        .isNull()
                        .and(DSL.val(from.doorsAt()).isNotNull())
                        .or(GIG.TICKET_URL
                                .isNull()
                                .and(DSL.val(from.ticketUrl()).isNotNull()))
                        .or(GIG.PRICE_MIN.isNull().and(DSL.val(from.priceMin()).isNotNull()))
                        .or(GIG.PRICE_MAX.isNull().and(DSL.val(from.priceMax()).isNotNull()))
                        .or(GIG.IMAGE_URL.isNull().and(DSL.val(from.imageUrl()).isNotNull())))
                .execute();
        List<UUID> bill = dsl.select(GIG_ARTIST.ARTIST_ID)
                .from(GIG_ARTIST)
                .where(GIG_ARTIST.GIG_ID.eq(gigId))
                .orderBy(GIG_ARTIST.BILLING_ORDER)
                .fetch(GIG_ARTIST.ARTIST_ID);
        int added = 0;
        for (UUID artistId : from.artistIds()) {
            if (!bill.contains(artistId)) {
                dsl.insertInto(GIG_ARTIST)
                        .set(GIG_ARTIST.GIG_ID, gigId)
                        .set(GIG_ARTIST.ARTIST_ID, artistId)
                        .set(GIG_ARTIST.BILLING_ORDER, (short) (bill.size() + added))
                        .execute();
                added++;
            }
        }
        return fields > 0 || added > 0;
    }

    @Override
    public UUID createGig(GigDraft gig) {
        UUID id = Ids.newId();
        OffsetDateTime now = now();
        dsl.insertInto(GIG)
                .set(GIG.ID, id)
                .set(GIG.TITLE, gig.title())
                .set(GIG.VENUE_ID, gig.venueId())
                .set(GIG.STARTS_AT, at(gig.startsAt()))
                .set(GIG.DOORS_AT, gig.doorsAt() == null ? null : at(gig.doorsAt()))
                .set(GIG.STATUS, gig.status())
                .set(GIG.TICKET_URL, gig.ticketUrl())
                .set(GIG.PRICE_MIN, gig.priceMin())
                .set(GIG.PRICE_MAX, gig.priceMax())
                .set(GIG.CURRENCY, gig.currency())
                .set(GIG.IMAGE_URL, gig.imageUrl())
                .set(GIG.CONTENT_HASH, gig.contentHash())
                .set(GIG.CREATED_AT, now)
                .set(GIG.UPDATED_AT, now)
                .execute();
        copyVenueLocation(id, gig.venueId());
        insertLineup(id, gig.artistIds());
        return id;
    }

    @Override
    public void updateGig(UUID gigId, GigDraft gig) {
        dsl.update(GIG)
                .set(GIG.TITLE, gig.title())
                .set(GIG.VENUE_ID, gig.venueId())
                .set(GIG.STARTS_AT, at(gig.startsAt()))
                .set(GIG.DOORS_AT, gig.doorsAt() == null ? null : at(gig.doorsAt()))
                .set(GIG.STATUS, gig.status())
                .set(GIG.TICKET_URL, gig.ticketUrl())
                .set(GIG.PRICE_MIN, gig.priceMin())
                .set(GIG.PRICE_MAX, gig.priceMax())
                .set(GIG.CURRENCY, gig.currency())
                .set(GIG.IMAGE_URL, gig.imageUrl())
                .set(GIG.CONTENT_HASH, gig.contentHash())
                .set(GIG.UPDATED_AT, now())
                .where(GIG.ID.eq(gigId))
                .execute();
        copyVenueLocation(gigId, gig.venueId());
        dsl.deleteFrom(GIG_ARTIST).where(GIG_ARTIST.GIG_ID.eq(gigId)).execute();
        insertLineup(gigId, gig.artistIds());
    }

    @Override
    public void linkGig(
            String source,
            String externalId,
            UUID gigId,
            @Nullable String url,
            Instant seenAt,
            boolean primary,
            boolean packageListing) {
        dsl.insertInto(GIG_SOURCE)
                .set(GIG_SOURCE.SOURCE, source)
                .set(GIG_SOURCE.EXTERNAL_ID, externalId)
                .set(GIG_SOURCE.GIG_ID, gigId)
                .set(GIG_SOURCE.URL, url)
                .set(GIG_SOURCE.LAST_SEEN_AT, at(seenAt))
                .set(GIG_SOURCE.PRIMARY_LISTING, primary)
                .set(GIG_SOURCE.PACKAGE_LISTING, packageListing)
                .onConflict(GIG_SOURCE.SOURCE, GIG_SOURCE.EXTERNAL_ID)
                .doUpdate()
                .set(GIG_SOURCE.URL, url)
                .set(GIG_SOURCE.LAST_SEEN_AT, at(seenAt))
                .set(GIG_SOURCE.PACKAGE_LISTING, packageListing)
                .execute();
        // Listed again: a gig marked UNLISTED comes back.
        dsl.update(GIG)
                .set(GIG.STATUS, "SCHEDULED")
                .set(GIG.UPDATED_AT, now())
                .where(GIG.ID.eq(gigId))
                .and(GIG.STATUS.eq(UNLISTED))
                .execute();
    }

    @Override
    public void makePrimary(UUID gigId, String source, String externalId) {
        // Clear first: the partial unique index allows one primary listing per gig at any moment.
        dsl.update(GIG_SOURCE)
                .set(GIG_SOURCE.PRIMARY_LISTING, false)
                .where(GIG_SOURCE.GIG_ID.eq(gigId))
                .and(GIG_SOURCE.PRIMARY_LISTING.isTrue())
                .execute();
        dsl.update(GIG_SOURCE)
                .set(GIG_SOURCE.PRIMARY_LISTING, true)
                .where(GIG_SOURCE.SOURCE.eq(source))
                .and(GIG_SOURCE.EXTERNAL_ID.eq(externalId))
                .and(GIG_SOURCE.GIG_ID.eq(gigId))
                .execute();
    }

    @Override
    public void completeVenue(UUID venueId, @Nullable Double lat, @Nullable Double lng) {
        if (lat == null || lng == null) {
            return;
        }
        int filled = dsl.update(VENUE)
                .set(Map.of(VENUE.LOCATION, point(lat, lng)))
                .set(VENUE.UPDATED_AT, now())
                .where(VENUE.ID.eq(venueId))
                .and(VENUE.LOCATION.isNull())
                .execute();
        if (filled == 1) {
            dsl.update(GIG)
                    .set(Map.of(GIG.LOCATION, point(lat, lng)))
                    .where(GIG.VENUE_ID.eq(venueId))
                    .and(GIG.LOCATION.isNull())
                    .execute();
        }
    }

    /** Central London (Charing Cross), for notes on venues with uncommon names. */
    private static final double LONDON_LAT = 51.5074;

    private static final double LONDON_LNG = -0.1278;

    @Override
    @Transactional
    public int applyCoverageNotes(List<VenueNote> notes) {
        dsl.update(VENUE)
                .setNull(VENUE.COVERAGE_NOTE)
                .where(VENUE.COVERAGE_NOTE.isNotNull())
                .execute();
        int noted = 0;
        for (VenueNote note : notes) {
            List<String> names = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(note.name()), note.aliases().stream())
                    .map(NameNormaliser::normalise)
                    .distinct()
                    .toList();
            Condition where;
            if (note.postcodePrefix() != null) {
                // A common name ("The Half Moon"): only the one at that postcode district.
                where = DSL.upper(DSL.replace(VENUE.POSTCODE, " ", ""))
                        .startsWith(note.postcodePrefix().replace(" ", "").toUpperCase(java.util.Locale.ROOT));
            } else {
                where = DSL.condition(
                                "ST_DWithin({0}, {1}, {2})",
                                VENUE.LOCATION, point(LONDON_LAT, LONDON_LNG), DSL.val(25_000.0))
                        .or(VENUE.LOCATION.isNull().and(VENUE.CITY.likeIgnoreCase("%london%")));
            }
            // Expected index: venue_name_norm_trgm (pg_trgm GIN serves equality since PostgreSQL 14); runs at
            // start-up and before the daily ingestion, for a few dozen notes.
            noted += dsl.update(VENUE)
                    .set(VENUE.COVERAGE_NOTE, note.note())
                    .where(VENUE.NAME_NORM.in(names))
                    .and(where)
                    .execute();
        }
        return noted;
    }

    @Override
    @Transactional(readOnly = true)
    public List<VenueToLocate> venuesWithoutLocation(Instant now, int limit) {
        Field<Boolean> upcoming = DSL.field(DSL.exists(
                DSL.selectOne().from(GIG).where(GIG.VENUE_ID.eq(VENUE.ID)).and(GIG.STARTS_AT.ge(at(now)))));
        // Runs once per ingestion run over a few thousand venues; no dedicated index.
        return dsl.select(VENUE.ID, VENUE.NAME, VENUE.POSTCODE, VENUE.CITY)
                .from(VENUE)
                .where(VENUE.LOCATION.isNull())
                .orderBy(upcoming.desc(), VENUE.NAME, VENUE.ID)
                .limit(limit)
                .fetch(r -> new VenueToLocate(r.value1(), r.value2(), r.value3(), r.value4()));
    }

    @Override
    @Transactional(readOnly = true)
    public long countListings(String source, @Nullable String externalIdPrefix) {
        Condition where = GIG_SOURCE.SOURCE.eq(source);
        if (externalIdPrefix != null) {
            where = where.and(GIG_SOURCE.EXTERNAL_ID.like(DSL.escape(externalIdPrefix, '!') + "%", '!'));
        }
        return dsl.fetchCount(GIG_SOURCE, where);
    }

    @Override
    public int unlistNotSeenSince(String source, String externalIdPrefix, Instant before, Instant now) {
        var listing = GIG_SOURCE.as("listing");
        // Once per venue site per run; gig_source is scanned by source (no dedicated index, as above).
        return dsl.update(GIG)
                .set(GIG.STATUS, UNLISTED)
                .set(GIG.UPDATED_AT, now())
                .where(GIG.STATUS.eq("SCHEDULED"))
                .and(GIG.STARTS_AT.gt(at(now)))
                .and(DSL.exists(DSL.selectOne()
                        .from(listing)
                        .where(listing.GIG_ID.eq(GIG.ID))
                        .and(listing.SOURCE.eq(source))
                        .and(listing.EXTERNAL_ID.like(DSL.escape(externalIdPrefix, '!') + "%", '!'))))
                .and(DSL.notExists(DSL.selectOne()
                        .from(listing)
                        .where(listing.GIG_ID.eq(GIG.ID))
                        .and(listing.SOURCE.ne(source).or(listing.LAST_SEEN_AT.ge(at(before))))))
                .execute();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> futureGigsNotSeenSince(String source, Instant before, Instant now, int limit) {
        // Runs once per ingestion run; no dedicated index (gig_source is scanned by source).
        return dsl.select(GIG_SOURCE.EXTERNAL_ID)
                .from(GIG_SOURCE)
                .join(GIG)
                .on(GIG.ID.eq(GIG_SOURCE.GIG_ID))
                .where(GIG_SOURCE.SOURCE.eq(source))
                .and(GIG_SOURCE.LAST_SEEN_AT.lt(at(before)))
                .and(GIG.STARTS_AT.gt(at(now)))
                .orderBy(GIG.STARTS_AT)
                .limit(limit)
                .fetch(GIG_SOURCE.EXTERNAL_ID);
    }

    private GigRef ref(UUID gigId) {
        var gig = dsl.select(GIG.CONTENT_HASH, GIG.TITLE, GIG.STARTS_AT)
                .from(GIG)
                .where(GIG.ID.eq(gigId))
                .fetchSingle();
        List<GigRef.Listing> listings = dsl.select(
                        GIG_SOURCE.SOURCE,
                        GIG_SOURCE.EXTERNAL_ID,
                        GIG_SOURCE.PRIMARY_LISTING,
                        GIG_SOURCE.LAST_SEEN_AT,
                        GIG_SOURCE.PACKAGE_LISTING)
                .from(GIG_SOURCE)
                .where(GIG_SOURCE.GIG_ID.eq(gigId))
                .fetch(r -> new GigRef.Listing(
                        r.value1(), r.value2(), r.value3(), r.value4().toInstant(), r.value5()));
        return new GigRef(gigId, gig.value1(), gig.value2(), gig.value3().toInstant(), listings);
    }

    /** {@code gig.location} is a copy of the venue's point so geo + time filters touch one table. */
    private void copyVenueLocation(UUID gigId, UUID venueId) {
        dsl.update(GIG)
                .set(Map.of(
                        GIG.LOCATION,
                        DSL.select(VENUE.LOCATION)
                                .from(VENUE)
                                .where(VENUE.ID.eq(venueId))
                                .asField()))
                .where(GIG.ID.eq(gigId))
                .execute();
    }

    private void insertLineup(UUID gigId, List<UUID> artistIds) {
        for (int order = 0; order < artistIds.size(); order++) {
            dsl.insertInto(GIG_ARTIST)
                    .set(GIG_ARTIST.GIG_ID, gigId)
                    .set(GIG_ARTIST.ARTIST_ID, artistIds.get(order))
                    .set(GIG_ARTIST.BILLING_ORDER, (short) order)
                    .execute();
        }
    }

    private static Field<Double> similarity(Field<String> column, String value) {
        return DSL.field("similarity({0}, {1})", Double.class, column, DSL.val(value));
    }

    private static Field<Object> point(double lat, double lng) {
        return DSL.field(
                "ST_SetSRID(ST_MakePoint({0}, {1}), 4326)::geography", Object.class, DSL.val(lng), DSL.val(lat));
    }

    static String compactPostcode(String postcode) {
        return postcode.replace(" ", "").toUpperCase(Locale.ROOT);
    }

    private OffsetDateTime now() {
        return at(Instant.now(clock));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
