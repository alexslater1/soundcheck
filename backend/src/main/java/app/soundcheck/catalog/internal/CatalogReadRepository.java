package app.soundcheck.catalog.internal;

import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST_GENRE;
import static app.soundcheck.shared.jooq.catalog.Tables.GENRE;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ATTENDANCE;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_SOURCE;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE;

import app.soundcheck.catalog.GeoPoint;
import app.soundcheck.catalog.NameNormaliser;
import app.soundcheck.shared.web.Cursor;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Read-side SQL of the catalog HTTP API: gigs, line-ups, artists, venues, attendance. */
@Repository
class CatalogReadRepository {

    static final Field<Double> VENUE_LAT = DSL.field("ST_Y({0}::geometry)", Double.class, VENUE.LOCATION);
    static final Field<Double> VENUE_LNG = DSL.field("ST_X({0}::geometry)", Double.class, VENUE.LOCATION);

    /** What to list. Every part is optional except the page size. */
    record GigFilter(
            @Nullable GeoPoint origin,
            @Nullable Integer radiusKm,
            @Nullable Instant from,
            @Nullable Instant to,
            @Nullable Set<Short> genreIds,
            @Nullable String q,
            @Nullable UUID artistId,
            @Nullable UUID venueId,
            @Nullable UUID attendeeId,
            @Nullable String attendanceStatus,
            boolean newestFirst,
            @Nullable Cursor cursor,
            int limit) {}

    record GigRow(
            UUID id,
            String title,
            Instant startsAt,
            String status,
            @Nullable String imageUrl,
            @Nullable String ticketUrl,
            @Nullable BigDecimal priceMin,
            @Nullable BigDecimal priceMax,
            @Nullable String currency,
            UUID venueId,
            String venueName,
            @Nullable String venueCity,
            @Nullable Double venueLat,
            @Nullable Double venueLng,
            @Nullable Double distanceKm) {}

    record ArtistRow(UUID id, String name, @Nullable String imageUrl, @Nullable UUID mbid) {}

    record LineupEntry(UUID gigId, UUID artistId, int billingOrder) {}

    record VenueRow(
            UUID id,
            String name,
            @Nullable String address,
            @Nullable String city,
            @Nullable String postcode,
            @Nullable Double lat,
            @Nullable Double lng,
            String timezone) {}

    record SourceLink(String source, @Nullable String url) {}

    private final DSLContext dsl;

    CatalogReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ── gigs ────────────────────────────────────────────────────────────────

    /**
     * One page of gigs in {@code (starts_at, id)} order, fetching {@code limit + 1} rows so the caller can
     * tell whether a next page exists. Expected indexes: {@code gig_starts_at (starts_at, id)} for the
     * keyset walk; {@code gig_location_gix} for the radius; {@code gig_venue} / {@code gig_artist_artist} /
     * the {@code gig_attendance} primary key when filtering by venue, artist or attendee.
     */
    List<GigRow> gigs(GigFilter filter) {
        Field<Double> distanceKm = filter.origin() == null
                ? DSL.inline((Double) null)
                : DSL.field(
                        "round((ST_Distance({0}, {1}) / 1000.0)::numeric, 1)::float8",
                        Double.class, GIG.LOCATION, point(filter.origin()));
        List<Condition> where = new ArrayList<>();
        if (filter.origin() != null && filter.radiusKm() != null) {
            where.add(DSL.condition(
                    "ST_DWithin({0}, {1}, {2})",
                    GIG.LOCATION, point(filter.origin()), DSL.val(filter.radiusKm() * 1000.0)));
        }
        if (filter.from() != null) {
            where.add(GIG.STARTS_AT.ge(at(filter.from())));
        }
        if (filter.to() != null) {
            where.add(GIG.STARTS_AT.lt(at(filter.to())));
        }
        if (filter.genreIds() != null) {
            where.add(DSL.exists(DSL.selectOne()
                    .from(GIG_ARTIST)
                    .join(ARTIST_GENRE)
                    .on(ARTIST_GENRE.ARTIST_ID.eq(GIG_ARTIST.ARTIST_ID))
                    .where(GIG_ARTIST.GIG_ID.eq(GIG.ID))
                    .and(ARTIST_GENRE.GENRE_ID.in(filter.genreIds()))));
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            // Title (case-insensitive substring), the venue's name, or any act on the bill (normalised
            // substrings). No index on the title: the other filters (window, radius) bound the scan.
            String raw = "%" + DSL.escape(filter.q().strip(), '!') + "%";
            String norm = "%" + DSL.escape(NameNormaliser.normalise(filter.q()), '!') + "%";
            where.add(GIG.TITLE
                    .likeIgnoreCase(raw, '!')
                    .or(VENUE.NAME_NORM.like(norm, '!'))
                    .or(DSL.exists(DSL.selectOne()
                            .from(GIG_ARTIST)
                            .join(ARTIST)
                            .on(ARTIST.ID.eq(GIG_ARTIST.ARTIST_ID))
                            .where(GIG_ARTIST.GIG_ID.eq(GIG.ID))
                            .and(ARTIST.NAME_NORM.like(norm, '!')))));
        }
        if (filter.artistId() != null) {
            where.add(DSL.exists(DSL.selectOne()
                    .from(GIG_ARTIST)
                    .where(GIG_ARTIST.GIG_ID.eq(GIG.ID))
                    .and(GIG_ARTIST.ARTIST_ID.eq(filter.artistId()))));
        }
        if (filter.venueId() != null) {
            where.add(GIG.VENUE_ID.eq(filter.venueId()));
        }
        if (filter.attendeeId() != null) {
            Condition mine = GIG_ATTENDANCE.GIG_ID.eq(GIG.ID).and(GIG_ATTENDANCE.USER_ID.eq(filter.attendeeId()));
            if (filter.attendanceStatus() != null) {
                mine = mine.and(GIG_ATTENDANCE.STATUS.eq(filter.attendanceStatus()));
            }
            where.add(DSL.exists(DSL.selectOne().from(GIG_ATTENDANCE).where(mine)));
        }
        if (filter.cursor() != null) {
            var last = DSL.row(at(filter.cursor().at()), filter.cursor().id());
            var key = DSL.row(GIG.STARTS_AT, GIG.ID);
            where.add(filter.newestFirst() ? key.lt(last) : key.gt(last));
        }
        return dsl.select(
                        GIG.ID,
                        GIG.TITLE,
                        GIG.STARTS_AT,
                        GIG.STATUS,
                        GIG.IMAGE_URL,
                        GIG.TICKET_URL,
                        GIG.PRICE_MIN,
                        GIG.PRICE_MAX,
                        GIG.CURRENCY,
                        VENUE.ID,
                        VENUE.NAME,
                        VENUE.CITY,
                        VENUE_LAT,
                        VENUE_LNG,
                        distanceKm)
                .from(GIG)
                .join(VENUE)
                .on(VENUE.ID.eq(GIG.VENUE_ID))
                .where(where)
                .orderBy(
                        filter.newestFirst() ? GIG.STARTS_AT.desc() : GIG.STARTS_AT.asc(),
                        filter.newestFirst() ? GIG.ID.desc() : GIG.ID.asc())
                .limit(filter.limit() + 1)
                .fetch(r -> toGigRow(r, distanceKm));
    }

    Optional<GigRow> gig(UUID id, @Nullable GeoPoint origin) {
        Field<Double> distanceKm = origin == null
                ? DSL.inline((Double) null)
                : DSL.field(
                        "round((ST_Distance({0}, {1}) / 1000.0)::numeric, 1)::float8",
                        Double.class, GIG.LOCATION, point(origin));
        return dsl.select(
                        GIG.ID,
                        GIG.TITLE,
                        GIG.STARTS_AT,
                        GIG.STATUS,
                        GIG.IMAGE_URL,
                        GIG.TICKET_URL,
                        GIG.PRICE_MIN,
                        GIG.PRICE_MAX,
                        GIG.CURRENCY,
                        VENUE.ID,
                        VENUE.NAME,
                        VENUE.CITY,
                        VENUE_LAT,
                        VENUE_LNG,
                        distanceKm)
                .from(GIG)
                .join(VENUE)
                .on(VENUE.ID.eq(GIG.VENUE_ID))
                .where(GIG.ID.eq(id))
                .fetchOptional(r -> toGigRow(r, distanceKm));
    }

    /** Gigs by id (no distance). Expected index: the primary key. */
    List<GigRow> gigsByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Field<Double> noDistance = DSL.inline((Double) null);
        return dsl.select(
                        GIG.ID,
                        GIG.TITLE,
                        GIG.STARTS_AT,
                        GIG.STATUS,
                        GIG.IMAGE_URL,
                        GIG.TICKET_URL,
                        GIG.PRICE_MIN,
                        GIG.PRICE_MAX,
                        GIG.CURRENCY,
                        VENUE.ID,
                        VENUE.NAME,
                        VENUE.CITY,
                        VENUE_LAT,
                        VENUE_LNG,
                        noDistance)
                .from(GIG)
                .join(VENUE)
                .on(VENUE.ID.eq(GIG.VENUE_ID))
                .where(GIG.ID.in(ids))
                .fetch(r -> toGigRow(r, noDistance));
    }

    /** Expected index: gig_venue (venue_id, starts_at). */
    List<UUID> gigIdsAtVenue(UUID venueId, int limit) {
        return dsl.select(GIG.ID)
                .from(GIG)
                .where(GIG.VENUE_ID.eq(venueId))
                .orderBy(GIG.STARTS_AT.desc())
                .limit(limit)
                .fetch(GIG.ID);
    }

    boolean venueExists(UUID id) {
        return dsl.fetchExists(VENUE, VENUE.ID.eq(id));
    }

    boolean gigExists(UUID id) {
        return dsl.fetchExists(GIG, GIG.ID.eq(id));
    }

    /** The bills of the given gigs, each in billing order. Expected index: gig_artist primary key. */
    List<LineupEntry> lineups(Collection<UUID> gigIds) {
        if (gigIds.isEmpty()) {
            return List.of();
        }
        return dsl.select(GIG_ARTIST.GIG_ID, GIG_ARTIST.ARTIST_ID, GIG_ARTIST.BILLING_ORDER)
                .from(GIG_ARTIST)
                .where(GIG_ARTIST.GIG_ID.in(gigIds))
                .orderBy(GIG_ARTIST.GIG_ID, GIG_ARTIST.BILLING_ORDER)
                .fetch(r -> new LineupEntry(r.value1(), r.value2(), r.value3()));
    }

    List<SourceLink> sources(UUID gigId) {
        return dsl.select(GIG_SOURCE.SOURCE, GIG_SOURCE.URL)
                .from(GIG_SOURCE)
                .where(GIG_SOURCE.GIG_ID.eq(gigId))
                .orderBy(GIG_SOURCE.SOURCE, GIG_SOURCE.EXTERNAL_ID)
                .fetch(r -> new SourceLink(r.value1(), r.value2()));
    }

    // ── attendance ──────────────────────────────────────────────────────────

    /** The viewer's status for each of the gigs they have marked. */
    Map<UUID, String> attendance(UUID userId, Collection<UUID> gigIds) {
        Map<UUID, String> byGig = new HashMap<>();
        if (!gigIds.isEmpty()) {
            dsl.select(GIG_ATTENDANCE.GIG_ID, GIG_ATTENDANCE.STATUS)
                    .from(GIG_ATTENDANCE)
                    .where(GIG_ATTENDANCE.USER_ID.eq(userId))
                    .and(GIG_ATTENDANCE.GIG_ID.in(gigIds))
                    .forEach(r -> byGig.put(r.value1(), r.value2()));
        }
        return byGig;
    }

    /** Status → number of users. Expected index: gig_attendance_gig (gig_id, status). */
    Map<String, Long> attendanceCounts(UUID gigId) {
        Map<String, Long> counts = new HashMap<>();
        dsl.select(GIG_ATTENDANCE.STATUS, DSL.count())
                .from(GIG_ATTENDANCE)
                .where(GIG_ATTENDANCE.GIG_ID.eq(gigId))
                .groupBy(GIG_ATTENDANCE.STATUS)
                .forEach(r -> counts.put(r.value1(), r.value2().longValue()));
        return counts;
    }

    /** Upsert on the primary key {@code (user_id, gig_id)}: repeating the call changes nothing. */
    void setAttendance(UUID userId, UUID gigId, String status, Instant now) {
        dsl.insertInto(GIG_ATTENDANCE)
                .set(GIG_ATTENDANCE.USER_ID, userId)
                .set(GIG_ATTENDANCE.GIG_ID, gigId)
                .set(GIG_ATTENDANCE.STATUS, status)
                .set(GIG_ATTENDANCE.CREATED_AT, at(now))
                .set(GIG_ATTENDANCE.UPDATED_AT, at(now))
                .onConflict(GIG_ATTENDANCE.USER_ID, GIG_ATTENDANCE.GIG_ID)
                .doUpdate()
                .set(GIG_ATTENDANCE.STATUS, status)
                .set(GIG_ATTENDANCE.UPDATED_AT, at(now))
                .where(GIG_ATTENDANCE.STATUS.ne(status))
                .execute();
    }

    void clearAttendance(UUID userId, UUID gigId) {
        dsl.deleteFrom(GIG_ATTENDANCE)
                .where(GIG_ATTENDANCE.USER_ID.eq(userId))
                .and(GIG_ATTENDANCE.GIG_ID.eq(gigId))
                .execute();
    }

    // ── artists ─────────────────────────────────────────────────────────────

    Map<UUID, ArtistRow> artists(Collection<UUID> ids) {
        Map<UUID, ArtistRow> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            dsl.select(ARTIST.ID, ARTIST.NAME, ARTIST.IMAGE_URL, ARTIST.MBID)
                    .from(ARTIST)
                    .where(ARTIST.ID.in(ids))
                    .forEach(r -> byId.put(r.value1(), new ArtistRow(r.value1(), r.value2(), r.value3(), r.value4())));
        }
        return byId;
    }

    boolean artistExists(UUID id) {
        return dsl.fetchExists(ARTIST, ARTIST.ID.eq(id));
    }

    /** Genre slugs per artist, heaviest first. */
    Map<UUID, List<String>> genreSlugs(Collection<UUID> artistIds) {
        Map<UUID, List<String>> byArtist = new HashMap<>();
        if (!artistIds.isEmpty()) {
            dsl.select(ARTIST_GENRE.ARTIST_ID, GENRE.SLUG)
                    .from(ARTIST_GENRE)
                    .join(GENRE)
                    .on(GENRE.ID.eq(ARTIST_GENRE.GENRE_ID))
                    .where(ARTIST_GENRE.ARTIST_ID.in(artistIds))
                    .orderBy(ARTIST_GENRE.ARTIST_ID, ARTIST_GENRE.WEIGHT.desc(), GENRE.ID)
                    .forEach(r -> byArtist.computeIfAbsent(r.value1(), id -> new ArrayList<>())
                            .add(r.value2()));
        }
        return byArtist;
    }

    /** Genre id → weight per artist. Expected index: the artist_genre primary key. */
    Map<UUID, Map<Short, Double>> genreWeights(Collection<UUID> artistIds) {
        Map<UUID, Map<Short, Double>> byArtist = new HashMap<>();
        if (!artistIds.isEmpty()) {
            dsl.select(ARTIST_GENRE.ARTIST_ID, ARTIST_GENRE.GENRE_ID, ARTIST_GENRE.WEIGHT)
                    .from(ARTIST_GENRE)
                    .where(ARTIST_GENRE.ARTIST_ID.in(artistIds))
                    .forEach(r -> byArtist.computeIfAbsent(r.value1(), id -> new HashMap<>())
                            .put(r.value2(), r.value3().doubleValue()));
        }
        return byArtist;
    }

    /**
     * Name search: names starting with the query first, then names containing a word similar to it
     * (pg_trgm word similarity, so "radiohed" still finds Radiohead), most popular first within each.
     * Expected index: artist_name_norm_trgm (serves both LIKE 'prefix%' and the {@code <%} operator).
     */
    List<UUID> searchArtists(String query, int limit) {
        String norm = NameNormaliser.normalise(query);
        Condition prefix = ARTIST.NAME_NORM.like(DSL.escape(norm, '!') + "%", '!');
        Condition similar = DSL.condition("{0} <% {1}", DSL.val(norm), ARTIST.NAME_NORM);
        Field<Double> similarity =
                DSL.field("word_similarity({0}, {1})", Double.class, DSL.val(norm), ARTIST.NAME_NORM);
        return dsl.select(ARTIST.ID)
                .from(ARTIST)
                .where(prefix.or(similar))
                .orderBy(
                        DSL.when(prefix, 0).otherwise(1),
                        similarity.desc(),
                        ARTIST.POPULARITY.desc(),
                        ARTIST.NAME,
                        ARTIST.ID)
                .limit(limit)
                .fetch(ARTIST.ID);
    }

    /**
     * Artists for the onboarding "follow some artists" step: by stored popularity, then by how many
     * upcoming gigs they play (no job sets popularity yet, so it is 0 for everyone).
     * Expected index: gig_artist_artist (artist_id, gig_id) for the per-artist count.
     */
    List<UUID> popularArtists(Instant now, int limit) {
        Field<Integer> upcoming = DSL.field(DSL.selectCount()
                .from(GIG_ARTIST)
                .join(GIG)
                .on(GIG.ID.eq(GIG_ARTIST.GIG_ID))
                .where(GIG_ARTIST.ARTIST_ID.eq(ARTIST.ID))
                .and(GIG.STARTS_AT.ge(at(now))));
        return dsl.select(ARTIST.ID)
                .from(ARTIST)
                .orderBy(ARTIST.POPULARITY.desc(), upcoming.desc(), ARTIST.NAME, ARTIST.ID)
                .limit(limit)
                .fetch(ARTIST.ID);
    }

    // ── venues ──────────────────────────────────────────────────────────────

    Optional<VenueRow> venue(UUID id) {
        return dsl.select(
                        VENUE.ID,
                        VENUE.NAME,
                        VENUE.ADDRESS,
                        VENUE.CITY,
                        VENUE.POSTCODE,
                        VENUE_LAT,
                        VENUE_LNG,
                        VENUE.TIMEZONE)
                .from(VENUE)
                .where(VENUE.ID.eq(id))
                .fetchOptional(r -> new VenueRow(
                        r.value1(),
                        r.value2(),
                        r.value3(),
                        r.value4(),
                        r.value5(),
                        r.value6(),
                        r.value7(),
                        r.value8()));
    }

    /** Genre ids matching a slug: the genre itself plus its children ("rock" includes "indie"). */
    Set<Short> genreWithChildren(String slug) {
        Map<Short, Short> parents = new LinkedHashMap<>();
        Short[] wanted = new Short[1];
        dsl.select(GENRE.ID, GENRE.SLUG, GENRE.PARENT_ID).from(GENRE).forEach(r -> {
            parents.put(r.value1(), r.value3());
            if (r.value2().equalsIgnoreCase(slug)) {
                wanted[0] = r.value1();
            }
        });
        if (wanted[0] == null) {
            return Set.of();
        }
        Set<Short> ids = new java.util.HashSet<>();
        ids.add(wanted[0]);
        parents.forEach((id, parent) -> {
            if (wanted[0].equals(parent)) {
                ids.add(id);
            }
        });
        return ids;
    }

    private static GigRow toGigRow(Record r, Field<Double> distanceKm) {
        return new GigRow(
                r.get(GIG.ID),
                r.get(GIG.TITLE),
                r.get(GIG.STARTS_AT).toInstant(),
                r.get(GIG.STATUS),
                r.get(GIG.IMAGE_URL),
                r.get(GIG.TICKET_URL),
                r.get(GIG.PRICE_MIN),
                r.get(GIG.PRICE_MAX),
                r.get(GIG.CURRENCY),
                r.get(VENUE.ID),
                r.get(VENUE.NAME),
                r.get(VENUE.CITY),
                r.get(VENUE_LAT),
                r.get(VENUE_LNG),
                r.get(distanceKm));
    }

    private static Field<Object> point(GeoPoint p) {
        return DSL.field(
                "ST_SetSRID(ST_MakePoint({0}, {1}), 4326)::geography",
                Object.class, DSL.val(p.lng()), DSL.val(p.lat()));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
