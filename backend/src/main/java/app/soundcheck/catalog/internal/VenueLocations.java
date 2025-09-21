package app.soundcheck.catalog.internal;

import static app.soundcheck.shared.jooq.catalog.Tables.CITY;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE;

import app.soundcheck.catalog.GeoPoint;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Cursor;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Venues whose map position is probably wrong, and the operator's fix. A fan-added venue sits at
 * its city's centre until a source supplies coordinates, and {@code completeVenue} never
 * overwrites a location, so without this a wrong point stays for good. A fixed venue leaves the list (V153),
 * and its gigs move with it ({@code gig.location} is a copy of the venue's).
 */
@Service
@ConditionalOnRole(Roles.API)
public class VenueLocations {

    /** A venue this close to a town's centre point (catalog.city) was probably placed there, not found. */
    static final int CITY_CENTRE_METRES = 250;

    /** Mainland UK, Northern Ireland and the islands; generous, since a typo is what it catches. */
    static final double MIN_LAT = 49.8;

    static final double MAX_LAT = 60.9;
    static final double MIN_LNG = -8.7;
    static final double MAX_LNG = 1.8;

    @Schema(name = "SuspectVenue")
    public record SuspectVenue(
            UUID id,
            String name,
            @Schema(nullable = true) String city,
            @Schema(nullable = true) String postcode,
            @Schema(nullable = true) GeoPoint location,
            @Schema(description = "FAN_ADDED, CITY_CENTRE (within 250 m of a town's centre point), NO_LOCATION")
                    List<String> reasons,
            long gigs,
            Instant createdAt,
            @Schema(nullable = true, description = "The point on OpenStreetMap") String osmUrl) {}

    @Schema(name = "VenueLocationFixed")
    public record Fixed(UUID venueId, GeoPoint location, int gigsMoved, String osmUrl) {}

    private final DSLContext dsl;
    private final Clock clock;

    public VenueLocations(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    /**
     * Suspect venues not yet fixed, newest first, keyset on (created_at, id). No index serves the whole
     * condition: it scans catalog.venue (a few thousand rows), using venue_location_gix per town.
     */
    @Transactional(readOnly = true)
    public Page<SuspectVenue> suspects(@Nullable String cursor, @Nullable Integer limit) {
        Cursor after = Cursor.decode(cursor);
        int size = Cursor.limit(limit);
        Field<Boolean> fanAdded = DSL.field(VENUE.CREATED_BY.isNotNull());
        Field<Boolean> cityCentre = DSL.field(DSL.exists(DSL.selectOne()
                .from(CITY)
                .where(DSL.condition(
                        "ST_DWithin({0}, {1}, {2})", CITY.LOCATION, VENUE.LOCATION, DSL.val(CITY_CENTRE_METRES)))));
        Field<Boolean> noLocation = DSL.field(VENUE.LOCATION.isNull());
        Condition where = VENUE.LOCATION_FIXED_AT
                .isNull()
                .and(DSL.condition(fanAdded).or(DSL.condition(noLocation)).or(DSL.condition(cityCentre)));
        if (after != null) {
            where = where.and(
                    DSL.row(VENUE.CREATED_AT, VENUE.ID).lt(DSL.row(after.at().atOffset(ZoneOffset.UTC), after.id())));
        }
        Field<Integer> gigs = DSL.field(DSL.selectCount().from(GIG).where(GIG.VENUE_ID.eq(VENUE.ID)));
        var rows = dsl.select(
                        VENUE.ID,
                        VENUE.NAME,
                        VENUE.CITY,
                        VENUE.POSTCODE,
                        CatalogReadRepository.VENUE_LAT,
                        CatalogReadRepository.VENUE_LNG,
                        fanAdded,
                        cityCentre,
                        noLocation,
                        gigs,
                        VENUE.CREATED_AT)
                .from(VENUE)
                .where(where)
                .orderBy(VENUE.CREATED_AT.desc(), VENUE.ID.desc())
                .limit(size + 1)
                .fetch();
        List<SuspectVenue> items = new ArrayList<>();
        for (var r : rows.subList(0, Math.min(size, rows.size()))) {
            List<String> reasons = new ArrayList<>();
            if (Boolean.TRUE.equals(r.value7())) {
                reasons.add("FAN_ADDED");
            }
            if (Boolean.TRUE.equals(r.value8())) {
                reasons.add("CITY_CENTRE");
            }
            if (Boolean.TRUE.equals(r.value9())) {
                reasons.add("NO_LOCATION");
            }
            GeoPoint at = r.value5() == null || r.value6() == null ? null : new GeoPoint(r.value5(), r.value6());
            items.add(new SuspectVenue(
                    r.value1(),
                    r.value2(),
                    r.value3(),
                    r.value4(),
                    at,
                    reasons,
                    r.value10(),
                    r.value11().toInstant(),
                    at == null ? null : osmUrl(at.lat(), at.lng())));
        }
        if (rows.size() <= size) {
            return Page.of(items);
        }
        SuspectVenue last = items.getLast();
        return new Page<>(items, new Cursor(last.createdAt(), last.id()).encode());
    }

    /**
     * Puts the venue at the point and moves every gig there, in one transaction.
     *
     * @throws ApiException 400 {@code LOCATION_OUTSIDE_UK}; 404 {@code VENUE_NOT_FOUND}
     */
    @Transactional
    public Fixed fix(UUID venueId, double lat, double lng, UUID adminId) {
        if (!(lat >= MIN_LAT && lat <= MAX_LAT && lng >= MIN_LNG && lng <= MAX_LNG)) {
            throw ApiException.badRequest(
                    "LOCATION_OUTSIDE_UK",
                    "The point must be in the UK (latitude 49.8 to 60.9, longitude -8.7 to 1.8): latitude first");
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Field<Object> point = DSL.field(
                "ST_SetSRID(ST_MakePoint({0}, {1}), 4326)::geography", Object.class, DSL.val(lng), DSL.val(lat));
        int updated = dsl.update(VENUE)
                .set(Map.of(VENUE.LOCATION, point))
                .set(VENUE.LOCATION_FIXED_AT, now)
                .set(VENUE.LOCATION_FIXED_BY, adminId)
                .set(VENUE.UPDATED_AT, now)
                .where(VENUE.ID.eq(venueId))
                .execute();
        if (updated == 0) {
            throw ApiException.notFound("VENUE_NOT_FOUND", "No venue " + venueId);
        }
        // Expected index: gig_venue (venue_id, starts_at).
        int gigs = dsl.update(GIG)
                .set(Map.of(GIG.LOCATION, point))
                .set(GIG.UPDATED_AT, now)
                .where(GIG.VENUE_ID.eq(venueId))
                .execute();
        return new Fixed(venueId, new GeoPoint(lat, lng), gigs, osmUrl(lat, lng));
    }

    static String osmUrl(double lat, double lng) {
        String la = String.format(Locale.ROOT, "%.6f", lat);
        String lo = String.format(Locale.ROOT, "%.6f", lng);
        return "https://www.openstreetmap.org/?mlat=" + la + "&mlon=" + lo + "#map=18/" + la + "/" + lo;
    }
}
