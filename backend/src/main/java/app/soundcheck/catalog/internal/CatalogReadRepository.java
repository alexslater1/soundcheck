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
import java.time.LocalDate;
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
import org.jooq.Row2;
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
            /** Gigs with any of these acts on the bill; null or empty: any act. */
            @Nullable Set<UUID> artistIds,
            /** Gigs at any of these venues; null or empty: any venue. Both set: both must hold. */
            @Nullable Set<UUID> venueIds,
            @Nullable UUID attendeeId,
            @Nullable String attendanceStatus,
            boolean newestFirst,
            @Nullable Cursor cursor,
            int limit,
            /** Leave out cancelled gigs (a fan's stats: a cancelled gig did not happen). */
            boolean excludeCancelled) {

        /** Every list but the stats': cancelled gigs are shown, with their badge. */
        GigFilter(
                @Nullable GeoPoint origin,
                @Nullable Integer radiusKm,
                @Nullable Instant from,
                @Nullable Instant to,
                @Nullable Set<Short> genreIds,
                @Nullable String q,
                @Nullable Set<UUID> artistIds,
                @Nullable Set<UUID> venueIds,
                @Nullable UUID attendeeId,
                @Nullable String attendanceStatus,
                boolean newestFirst,
                @Nullable Cursor cursor,
                int limit) {
            this(
                    origin,
                    radiusKm,
                    from,
                    to,
                    genreIds,
                    q,
                    artistIds,
                    venueIds,
                    attendeeId,
                    attendanceStatus,
                    newestFirst,
                    cursor,
                    limit,
                    false);
        }
    }

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
            @Nullable Double distanceKm,
            boolean userAdded) {}

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
        Field<Double> distanceKm = distanceFrom(filter.origin());
        List<Condition> where = conditions(filter);
        if (filter.cursor() != null) {
            var last = DSL.row(at(filter.cursor().at()), filter.cursor().id());
            var key = DSL.row(GIG.STARTS_AT, GIG.ID);
            where.add(filter.newestFirst() ? key.lt(last) : key.gt(last));
        }
        return dsl.select(gigFields(distanceKm))
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

    /** One calendar day (UK time) and how many gigs matching a filter start on it. */
    record DayCount(LocalDate date, int count) {}

    /**
     * How many gigs match the filter on each UK calendar day, for the calendar view. Same conditions as
     * {@link #gigs}, without the cursor and the page size; days without a gig are absent. The window is
     * bounded by the caller, so this groups at most a few hundred rows. Expected indexes: as for
     * {@link #gigs} ({@code gig_starts_at} for the window).
     */
    List<DayCount> dayCounts(GigFilter filter) {
        Field<LocalDate> day = DSL.field("({0} at time zone 'Europe/London')::date", LocalDate.class, GIG.STARTS_AT);
        return dsl.select(day, DSL.count())
                .from(GIG)
                .join(VENUE)
                .on(VENUE.ID.eq(GIG.VENUE_ID))
                .where(conditions(filter))
                .groupBy(day)
                .orderBy(day)
                .fetch(r -> new DayCount(r.value1(), r.value2()));
    }

    /** The WHERE clause of a gig filter (everything but the keyset cursor). */
    private List<Condition> conditions(GigFilter filter) {
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
        if (filter.artistIds() != null && !filter.artistIds().isEmpty()) {
            where.add(DSL.exists(DSL.selectOne()
                    .from(GIG_ARTIST)
                    .where(GIG_ARTIST.GIG_ID.eq(GIG.ID))
                    .and(GIG_ARTIST.ARTIST_ID.in(filter.artistIds()))));
        }
        if (filter.venueIds() != null && !filter.venueIds().isEmpty()) {
            where.add(GIG.VENUE_ID.in(filter.venueIds()));
        }
        if (filter.excludeCancelled()) {
            where.add(GIG.STATUS.ne("CANCELLED"));
        }
        if (filter.attendeeId() != null) {
            Condition mine = GIG_ATTENDANCE.GIG_ID.eq(GIG.ID).and(GIG_ATTENDANCE.USER_ID.eq(filter.attendeeId()));
            if (filter.attendanceStatus() != null) {
                mine = mine.and(GIG_ATTENDANCE.STATUS.eq(filter.attendanceStatus()));
            }
            where.add(DSL.exists(DSL.selectOne().from(GIG_ATTENDANCE).where(mine)));
        }
        return where;
    }

    private Field<Double> distanceFrom(@Nullable GeoPoint origin) {
        return origin == null
                ? DSL.inline((Double) null)
                : DSL.field(
                        "round((ST_Distance({0}, {1}) / 1000.0)::numeric, 1)::float8",
                        Double.class, GIG.LOCATION, point(origin));
    }

    private static List<Field<?>> gigFields(Field<Double> distanceKm) {
        return List.of(
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
                distanceKm,
                GIG.CREATED_BY);
    }

    Optional<GigRow> gig(UUID id, @Nullable GeoPoint origin) {
        Field<Double> distanceKm = distanceFrom(origin);
        return dsl.select(gigFields(distanceKm))
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
        Field<Double> noDistance = distanceFrom(null);
        return dsl.select(gigFields(noDistance))
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
    /** The gig's id at a source (its setlist.fm id, say), if that source lists it. Index: gig_source_gig. */
    Optional<String> externalId(UUID gigId, String source) {
        return dsl.select(GIG_SOURCE.EXTERNAL_ID)
                .from(GIG_SOURCE)
                .where(GIG_SOURCE.GIG_ID.eq(gigId))
                .and(GIG_SOURCE.SOURCE.eq(source))
                .limit(1)
                .fetchOptional(GIG_SOURCE.EXTERNAL_ID);
    }

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

    /** Every gig the user has marked, newest mark first. Expected index: the gig_attendance primary key. */
    Map<UUID, String> attendanceOf(UUID userId, int limit) {
        Map<UUID, String> byGig = new HashMap<>();
        dsl.select(GIG_ATTENDANCE.GIG_ID, GIG_ATTENDANCE.STATUS)
                .from(GIG_ATTENDANCE)
                .where(GIG_ATTENDANCE.USER_ID.eq(userId))
                .orderBy(GIG_ATTENDANCE.UPDATED_AT.desc())
                .limit(limit)
                .forEach(r -> byGig.put(r.value1(), r.value2()));
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

    /**
     * Artists for the onboarding "follow some artists" step once genres are picked: artists
     * tagged with any of the genres, taking turns between the picked genres so that one big genre does
     * not fill the list. Within a genre, artists with upcoming gigs come first, then the strongest tag,
     * then the most upcoming gigs.
     *
     * @param pickedByGenre every genre to match (a picked one and its children) → the picked genre it counts for
     * Expected index: artist_genre_genre (genre_id) for the tags, gig_artist_artist for the gig counts.
     */
    List<UUID> popularArtistsInGenres(Instant now, Map<Short, Short> pickedByGenre, int limit) {
        if (pickedByGenre.isEmpty()) {
            return List.of();
        }
        Field<Short> genreId = DSL.field(DSL.name("m", "genre_id"), Short.class);
        Field<Short> pickedId = DSL.field(DSL.name("m", "picked_id"), Short.class);
        @SuppressWarnings("unchecked") // a generic array: every element is built as a Row2<Short, Short> here
        Row2<Short, Short>[] rows = pickedByGenre.entrySet().stream()
                .map(e -> DSL.row(e.getKey(), e.getValue()))
                .toArray(Row2[]::new);
        var mapping = DSL.values(rows).as("m", "genre_id", "picked_id");
        Field<Integer> upcoming = DSL.field(DSL.selectCount()
                .from(GIG_ARTIST)
                .join(GIG)
                .on(GIG.ID.eq(GIG_ARTIST.GIG_ID))
                .where(GIG_ARTIST.ARTIST_ID.eq(ARTIST_GENRE.ARTIST_ID))
                .and(GIG.STARTS_AT.ge(at(now))));
        var ranked = dsl.select(
                        ARTIST_GENRE.ARTIST_ID.as("artist_id"),
                        ARTIST_GENRE.WEIGHT.as("weight"),
                        DSL.rowNumber()
                                .over(DSL.partitionBy(pickedId)
                                        .orderBy(
                                                DSL.when(upcoming.gt(0), 0).otherwise(1),
                                                ARTIST_GENRE.WEIGHT.desc(),
                                                upcoming.desc(),
                                                ARTIST_GENRE.ARTIST_ID))
                                .as("turn"))
                .from(ARTIST_GENRE)
                .join(mapping)
                .on(genreId.eq(ARTIST_GENRE.GENRE_ID))
                .asTable("ranked");
        Field<UUID> artistId = ranked.field("artist_id", UUID.class);
        return dsl.select(artistId)
                .from(ranked)
                .groupBy(artistId)
                .orderBy(
                        DSL.min(ranked.field("turn", Integer.class)),
                        DSL.max(ranked.field("weight")).desc(),
                        artistId)
                .limit(limit)
                .fetch(artistId);
    }

    /** Each given genre and its children, mapped to the given genre ("rock" counts "indie" as rock). */
    Map<Short, Short> withChildren(Collection<Short> genreIds) {
        Map<Short, Short> mapped = new LinkedHashMap<>();
        dsl.select(GENRE.ID, GENRE.PARENT_ID).from(GENRE).forEach(r -> {
            if (genreIds.contains(r.value1())) {
                mapped.put(r.value1(), r.value1());
            } else if (r.value2() != null && genreIds.contains(r.value2())) {
                mapped.putIfAbsent(r.value1(), r.value2());
            }
        });
        return mapped;
    }

    // ── search suggestions ─────────────────────────────────────

    /**
     * A name that matches what was typed, with what the suggestion ranking needs: how well it matches
     * ({@link #matchTier}), how similar it is, how many gigs are coming up, and whether one of them (or the
     * venue itself) is near the viewer's home.
     */
    record NameMatch(
            UUID id, String name, @Nullable String city, int tier, double similarity, int upcoming, boolean nearHome) {}

    /**
     * How well a name matches the query, best first: 0 the whole name as typed ("The North"); 1 the same
     * name once normalised (leading "the", case, accents and punctuation ignored, so "north" finds "The
     * North"); 2 it starts with the query as whole words ("North Base"); 3 it starts with it
     * ("Northlane"); 4 a later word starts with it ("IWM North"); 5 only similar (a typo).
     */
    static Field<Integer> matchTier(Field<String> name, Field<String> nameNorm, String raw, String norm) {
        String escaped = DSL.escape(norm, '!');
        return DSL.when(DSL.lower(name).eq(raw.strip().toLowerCase(java.util.Locale.ROOT)), 0)
                .when(nameNorm.eq(norm), 1)
                .when(nameNorm.like(escaped + " %", '!'), 2)
                .when(nameNorm.like(escaped + "%", '!'), 3)
                .when(nameNorm.like("% " + escaped + "%", '!'), 4)
                .otherwise(5);
    }

    /** Names starting with the query, with a word starting with it, or with a similar word (pg_trgm). */
    private static Condition nameMatches(Field<String> nameNorm, String norm) {
        String escaped = DSL.escape(norm, '!');
        return nameNorm.like(escaped + "%", '!')
                .or(nameNorm.like("% " + escaped + "%", '!'))
                .or(DSL.condition("{0} <% {1}", DSL.val(norm), nameNorm));
    }

    /**
     * Artists whose name matches, best text match first (then most upcoming gigs): a pool the suggestion
     * service re-ranks by the viewer. Expected index: artist_name_norm_trgm (LIKE and {@code <%}); the gig
     * counts use gig_artist_artist and gig_starts_at.
     */
    List<NameMatch> artistMatches(String raw, Instant now, @Nullable GeoPoint home, int radiusKm, int limit) {
        String norm = NameNormaliser.normalise(raw);
        Field<Integer> tier = matchTier(ARTIST.NAME, ARTIST.NAME_NORM, raw, norm);
        Field<Double> similarity =
                DSL.field("word_similarity({0}, {1})", Double.class, DSL.val(norm), ARTIST.NAME_NORM);
        Field<Integer> upcoming = DSL.field(DSL.selectCount()
                .from(GIG_ARTIST)
                .join(GIG)
                .on(GIG.ID.eq(GIG_ARTIST.GIG_ID))
                .where(GIG_ARTIST.ARTIST_ID.eq(ARTIST.ID))
                .and(GIG.STARTS_AT.ge(at(now))));
        Field<Boolean> near = home == null
                ? DSL.inline(false)
                : DSL.field(DSL.exists(DSL.selectOne()
                        .from(GIG_ARTIST)
                        .join(GIG)
                        .on(GIG.ID.eq(GIG_ARTIST.GIG_ID))
                        .where(GIG_ARTIST.ARTIST_ID.eq(ARTIST.ID))
                        .and(GIG.STARTS_AT.ge(at(now)))
                        .and(DSL.condition(
                                "ST_DWithin({0}, {1}, {2})", GIG.LOCATION, point(home), DSL.val(radiusKm * 1000.0)))));
        return dsl.select(ARTIST.ID, ARTIST.NAME, tier, similarity, upcoming, near)
                .from(ARTIST)
                .where(nameMatches(ARTIST.NAME_NORM, norm))
                .orderBy(tier, upcoming.desc(), similarity.desc(), ARTIST.NAME, ARTIST.ID)
                .limit(limit)
                .fetch(r ->
                        new NameMatch(r.value1(), r.value2(), null, r.value3(), r.value4(), r.value5(), r.value6()));
    }

    /** Venues whose name matches, as {@link #artistMatches}. Expected index: venue_name_norm_trgm. */
    List<NameMatch> venueMatches(String raw, Instant now, @Nullable GeoPoint home, int radiusKm, int limit) {
        String norm = NameNormaliser.normalise(raw);
        Field<Integer> tier = matchTier(VENUE.NAME, VENUE.NAME_NORM, raw, norm);
        Field<Double> similarity = DSL.field("word_similarity({0}, {1})", Double.class, DSL.val(norm), VENUE.NAME_NORM);
        Field<Integer> upcoming = DSL.field(
                DSL.selectCount().from(GIG).where(GIG.VENUE_ID.eq(VENUE.ID)).and(GIG.STARTS_AT.ge(at(now))));
        Field<Boolean> near = home == null
                ? DSL.inline(false)
                : DSL.field(DSL.condition(
                        "coalesce(ST_DWithin({0}, {1}, {2}), false)",
                        VENUE.LOCATION, point(home), DSL.val(radiusKm * 1000.0)));
        return dsl.select(VENUE.ID, VENUE.NAME, VENUE.CITY, tier, similarity, upcoming, near)
                .from(VENUE)
                .where(nameMatches(VENUE.NAME_NORM, norm))
                .orderBy(tier, upcoming.desc(), similarity.desc(), VENUE.NAME, VENUE.ID)
                .limit(limit)
                .fetch(r -> new NameMatch(
                        r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7()));
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

    /** A venue in a search result: where it is and how far from the viewer's home (when known). */
    record VenueHit(UUID id, String name, @Nullable String city, @Nullable Double lat, @Nullable Double lng) {}

    /**
     * Venue name search for the "add a gig" picker: names starting with the query first, then names with
     * a similar word (pg_trgm), nearest to {@code origin} first within each, then by name. Expected index:
     * venue_name_norm_trgm (prefix LIKE and the {@code <%} operator).
     */
    List<VenueHit> searchVenues(String query, @Nullable GeoPoint origin, int limit) {
        String norm = NameNormaliser.normalise(query);
        Condition prefix = VENUE.NAME_NORM.like(DSL.escape(norm, '!') + "%", '!');
        Condition similar = DSL.condition("{0} <% {1}", DSL.val(norm), VENUE.NAME_NORM);
        Field<Double> distance = origin == null
                ? DSL.inline(0.0)
                : DSL.field("coalesce(ST_Distance({0}, {1}), 1e9)", Double.class, VENUE.LOCATION, point(origin));
        return dsl.select(VENUE.ID, VENUE.NAME, VENUE.CITY, VENUE_LAT, VENUE_LNG)
                .from(VENUE)
                .where(prefix.or(similar))
                .orderBy(DSL.when(prefix, 0).otherwise(1), distance, VENUE.NAME, VENUE.ID)
                .limit(limit)
                .fetch(r -> new VenueHit(r.value1(), r.value2(), r.value3(), r.value4(), r.value5()));
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
                r.get(distanceKm),
                r.get(GIG.CREATED_BY) != null);
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
