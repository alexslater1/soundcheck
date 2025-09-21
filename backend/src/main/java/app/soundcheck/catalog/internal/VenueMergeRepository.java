package app.soundcheck.catalog.internal;

import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.SAVED_GIG_FILTER;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE_ALIAS;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE_EXTERNAL_ID;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE_REDIRECT;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE_SIBLING;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/**
 * SQL for catalog's own part of a venue merge: the locks, the rows catalog owns (gigs,
 * the sources' venue ids, siblings, saved searches), the redirect and alias, and the delete. Like {@link
 * GigMergeRepository}, each statement moves every row of one table in one go.
 */
@Repository
class VenueMergeRepository {

    private final DSLContext dsl;

    VenueMergeRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Locks both venues ({@code for update}) lowest id first, and returns the ids that exist. */
    List<UUID> lock(UUID a, UUID b) {
        return dsl.select(VENUE.ID)
                .from(VENUE)
                .where(VENUE.ID.in(a, b))
                .orderBy(VENUE.ID)
                .forUpdate()
                .fetch(VENUE.ID);
    }

    /** One venue's identity for the preview. Expected index: the venue primary key. */
    record VenueFacts(
            UUID id,
            String name,
            String nameNorm,
            @Nullable String city,
            @Nullable String postcode,
            @Nullable Double lat,
            @Nullable Double lng,
            @Nullable UUID addedBy) {}

    Optional<VenueFacts> facts(UUID venueId) {
        return dsl.select(
                        VENUE.ID,
                        VENUE.NAME,
                        VENUE.NAME_NORM,
                        VENUE.CITY,
                        VENUE.POSTCODE,
                        CatalogReadRepository.VENUE_LAT,
                        CatalogReadRepository.VENUE_LNG,
                        VENUE.CREATED_BY)
                .from(VENUE)
                .where(VENUE.ID.eq(venueId))
                .fetchOptional(r -> new VenueFacts(
                        r.value1(),
                        r.value2(),
                        r.value3(),
                        r.value4(),
                        r.value5(),
                        r.value6(),
                        r.value7(),
                        r.value8()));
    }

    /** The sources' ids for the venue, "SOURCE:id". Expected index: none leads with venue_id; a small table. */
    List<String> externalIds(UUID venueId) {
        return dsl.select(VENUE_EXTERNAL_ID.SOURCE, VENUE_EXTERNAL_ID.EXTERNAL_ID)
                .from(VENUE_EXTERNAL_ID)
                .where(VENUE_EXTERNAL_ID.VENUE_ID.eq(venueId))
                .orderBy(VENUE_EXTERNAL_ID.SOURCE, VENUE_EXTERNAL_ID.EXTERNAL_ID)
                .fetch(r -> r.value1() + ":" + r.value2());
    }

    /**
     * What catalog keeps for the venue, by kind. Gigs use gig_venue; siblings their primary key; external
     * ids and saved searches (ids in an array, V030) scan small tables.
     */
    Map<String, Long> countsAt(UUID venueId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("gigs", (long) dsl.fetchCount(GIG, GIG.VENUE_ID.eq(venueId)));
        counts.put("listings", (long) dsl.fetchCount(VENUE_EXTERNAL_ID, VENUE_EXTERNAL_ID.VENUE_ID.eq(venueId)));
        counts.put("siblings", (long) dsl.fetchCount(VENUE_SIBLING, VENUE_SIBLING.VENUE_ID.eq(venueId)));
        counts.put("savedSearches", (long) dsl.fetchCount(
                SAVED_GIG_FILTER, DSL.condition("{0} = any({1})", DSL.val(venueId), SAVED_GIG_FILTER.VENUE_IDS)));
        return counts;
    }

    /** A gig of the duplicate and a gig of the survivor on the same UK day: maybe one show listed twice. */
    record SameNight(UUID movedGigId, String movedTitle, UUID gigId, String title, LocalDate day) {}

    /** Expected index: gig_venue (venue_id, starts_at) on both sides of the join. */
    List<SameNight> sameNights(UUID from, UUID into) {
        var f = GIG.as("f");
        var i = GIG.as("i");
        var fDay = DSL.field("({0} at time zone 'Europe/London')::date", LocalDate.class, f.STARTS_AT);
        var iDay = DSL.field("({0} at time zone 'Europe/London')::date", LocalDate.class, i.STARTS_AT);
        return dsl.select(f.ID, f.TITLE, i.ID, i.TITLE, fDay)
                .from(f)
                .join(i)
                .on(i.VENUE_ID.eq(into))
                .and(iDay.eq(fDay))
                .where(f.VENUE_ID.eq(from))
                .orderBy(fDay, f.ID, i.ID)
                .fetch(r -> new SameNight(r.value1(), r.value2(), r.value3(), r.value4(), r.value5()));
    }

    /**
     * The survivor keeps its own details and takes the duplicate's where it has none (an address, a
     * postcode, a city, a point), as {@code completeVenue} fills a missing location.
     */
    void fillSurvivor(UUID from, UUID into, Instant now) {
        dsl.execute(
                """
                update catalog.venue i
                   set address = coalesce(i.address, f.address),
                       postcode = coalesce(i.postcode, f.postcode),
                       city = coalesce(i.city, f.city),
                       location = coalesce(i.location, f.location),
                       updated_at = cast(? as timestamptz)
                  from catalog.venue f
                 where i.id = ? and f.id = ?
                """,
                at(now),
                into,
                from);
    }

    /** Every gig moves, and takes the survivor's point (gig.location is a copy of the venue's). */
    int moveGigs(UUID from, UUID into, Instant now) {
        return dsl.update(GIG)
                .set(GIG.VENUE_ID, into)
                .set(Map.of(
                        GIG.LOCATION,
                        DSL.select(VENUE.LOCATION)
                                .from(VENUE)
                                .where(VENUE.ID.eq(into))
                                .asField()))
                .set(GIG.UPDATED_AT, at(now))
                .where(GIG.VENUE_ID.eq(from))
                .execute();
    }

    /** The sources' ids move, so Ticketmaster and the venue scrapers land on the survivor next run. */
    void moveExternalIds(UUID from, UUID into) {
        dsl.update(VENUE_EXTERNAL_ID)
                .set(VENUE_EXTERNAL_ID.VENUE_ID, into)
                .where(VENUE_EXTERNAL_ID.VENUE_ID.eq(from))
                .execute();
    }

    /** The duplicate's siblings (not counting the survivor), then its sibling rows are removed. */
    List<UUID> takeSiblings(UUID from, UUID into) {
        List<UUID> siblings = dsl.select(VENUE_SIBLING.SIBLING_ID)
                .from(VENUE_SIBLING)
                .where(VENUE_SIBLING.VENUE_ID.eq(from))
                .and(VENUE_SIBLING.SIBLING_ID.ne(into))
                .fetch(VENUE_SIBLING.SIBLING_ID);
        dsl.deleteFrom(VENUE_SIBLING)
                .where(VENUE_SIBLING.VENUE_ID.eq(from).or(VENUE_SIBLING.SIBLING_ID.eq(from)))
                .execute();
        return siblings;
    }

    /** Saved searches naming the duplicate name the survivor, once, in the order they were saved. */
    void moveSavedSearches(UUID from, UUID into) {
        dsl.execute(
                """
                update catalog.saved_gig_filter
                   set venue_ids = (select array_agg(v order by first)
                                      from (select v, min(o) as first
                                              from unnest(array_replace(venue_ids, ?, ?)) with ordinality as u(v, o)
                                             group by v) d)
                 where ? = any(venue_ids)
                """,
                from,
                into,
                from);
    }

    /**
     * The duplicate's id redirects to the survivor, and so do the ids that redirected to it (a chain);
     * its name, and the names that led to it, now lead to the survivor.
     */
    void redirectAndAlias(UUID from, UUID into, String fromNameNorm, UUID adminId, Instant now) {
        dsl.update(VENUE_REDIRECT)
                .set(VENUE_REDIRECT.INTO_VENUE_ID, into)
                .where(VENUE_REDIRECT.INTO_VENUE_ID.eq(from))
                .execute();
        dsl.insertInto(VENUE_REDIRECT)
                .set(VENUE_REDIRECT.FROM_VENUE_ID, from)
                .set(VENUE_REDIRECT.INTO_VENUE_ID, into)
                .set(VENUE_REDIRECT.MERGED_AT, at(now))
                .set(VENUE_REDIRECT.MERGED_BY, adminId)
                .execute();
        dsl.update(VENUE_ALIAS)
                .set(VENUE_ALIAS.VENUE_ID, into)
                .where(VENUE_ALIAS.VENUE_ID.eq(from))
                .execute();
        dsl.insertInto(VENUE_ALIAS)
                .set(VENUE_ALIAS.NAME_NORM, fromNameNorm)
                .set(VENUE_ALIAS.VENUE_ID, into)
                .set(VENUE_ALIAS.CREATED_AT, at(now))
                .onConflict(VENUE_ALIAS.NAME_NORM)
                .doUpdate()
                .set(VENUE_ALIAS.VENUE_ID, into)
                .set(VENUE_ALIAS.CREATED_AT, at(now))
                .execute();
    }

    /** Nothing points at it any more; gig.venue_id has no cascade, so a missed gig fails the merge. */
    void deleteVenue(UUID venueId) {
        dsl.deleteFrom(VENUE).where(VENUE.ID.eq(venueId)).execute();
    }

    /** Where a merged venue's id now leads. Expected index: the venue_redirect primary key. */
    Optional<UUID> redirectOf(UUID venueId) {
        return dsl.select(VENUE_REDIRECT.INTO_VENUE_ID)
                .from(VENUE_REDIRECT)
                .where(VENUE_REDIRECT.FROM_VENUE_ID.eq(venueId))
                .fetchOptional(VENUE_REDIRECT.INTO_VENUE_ID);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
