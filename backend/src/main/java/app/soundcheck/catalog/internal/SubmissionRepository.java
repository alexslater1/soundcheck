package app.soundcheck.catalog.internal;

import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_EDIT;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE;

import app.soundcheck.shared.Ids;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** SQL for what users add to the catalog: who added a row, and fans' edits of gigs. */
@Repository
class SubmissionRepository {

    private final DSLContext dsl;

    SubmissionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Who added the gig; empty for a source's listing. Expected index: the gig primary key. */
    Optional<UUID> gigAddedBy(UUID gigId) {
        return dsl.select(GIG.CREATED_BY).from(GIG).where(GIG.ID.eq(gigId)).fetchOptional(GIG.CREATED_BY);
    }

    /** Appends an act to the bill, remembered as the fan's, unless it is on it already. */
    boolean addAct(UUID gigId, UUID artistId, UUID userId) {
        Short last = dsl.select(DSL.max(GIG_ARTIST.BILLING_ORDER))
                .from(GIG_ARTIST)
                .where(GIG_ARTIST.GIG_ID.eq(gigId))
                .fetchOne(0, Short.class);
        return dsl.insertInto(GIG_ARTIST)
                        .set(GIG_ARTIST.GIG_ID, gigId)
                        .set(GIG_ARTIST.ARTIST_ID, artistId)
                        .set(GIG_ARTIST.BILLING_ORDER, (short) (last == null ? 0 : last + 1))
                        .set(GIG_ARTIST.ADDED_BY, userId)
                        .onConflictDoNothing()
                        .execute()
                > 0;
    }

    void setStartsAt(UUID gigId, Instant startsAt, Instant now) {
        dsl.update(GIG)
                .set(GIG.STARTS_AT, startsAt.atOffset(ZoneOffset.UTC))
                .set(GIG.UPDATED_AT, now.atOffset(ZoneOffset.UTC))
                .where(GIG.ID.eq(gigId))
                .execute();
    }

    /** Moves the gig to another venue, and its point with it. */
    void setVenue(UUID gigId, UUID venueId, Instant now) {
        dsl.update(GIG)
                .set(GIG.VENUE_ID, venueId)
                .set(Map.of(
                        GIG.LOCATION,
                        DSL.select(VENUE.LOCATION)
                                .from(VENUE)
                                .where(VENUE.ID.eq(venueId))
                                .asField()))
                .set(GIG.UPDATED_AT, now.atOffset(ZoneOffset.UTC))
                .where(GIG.ID.eq(gigId))
                .execute();
    }

    /**
     * The act becomes the headliner (billing order 0); the rest keep their order after
     * it, each keeping who added it. Expected index: the {@code gig_artist} primary key (gig first).
     */
    void setHeadliner(UUID gigId, UUID artistId) {
        var bill = dsl.select(GIG_ARTIST.ARTIST_ID, GIG_ARTIST.ADDED_BY)
                .from(GIG_ARTIST)
                .where(GIG_ARTIST.GIG_ID.eq(gigId))
                .orderBy(GIG_ARTIST.BILLING_ORDER)
                .fetch();
        short order = 1;
        for (var act : bill) {
            dsl.update(GIG_ARTIST)
                    .set(GIG_ARTIST.BILLING_ORDER, act.value1().equals(artistId) ? 0 : order++)
                    .where(GIG_ARTIST.GIG_ID.eq(gigId))
                    .and(GIG_ARTIST.ARTIST_ID.eq(act.value1()))
                    .execute();
        }
    }

    /** Acts' names by id. Expected index: the artist primary key. */
    Map<UUID, String> artistNames(List<UUID> artistIds) {
        return dsl.select(ARTIST.ID, ARTIST.NAME)
                .from(ARTIST)
                .where(ARTIST.ID.in(artistIds))
                .fetchMap(ARTIST.ID, ARTIST.NAME);
    }

    void setTitle(UUID gigId, String title, Instant now) {
        dsl.update(GIG)
                .set(GIG.TITLE, title)
                .set(GIG.UPDATED_AT, now.atOffset(ZoneOffset.UTC))
                .where(GIG.ID.eq(gigId))
                .execute();
    }

    /** One line of a gig's edit history: who changed what, from what, to what. */
    void logEdit(UUID gigId, UUID userId, String field, @Nullable String oldValue, String newValue, Instant now) {
        logEdit(gigId, userId, field, oldValue, newValue, now, null);
    }

    /**
     * An edit, or an operator's undo of one ({@code undoesEditId}, V152). An undo of ACT_ADDED that
     * takes the act off the bill has no new value.
     */
    UUID logEdit(
            UUID gigId,
            UUID userId,
            String field,
            @Nullable String oldValue,
            @Nullable String newValue,
            Instant now,
            @Nullable UUID undoesEditId) {
        UUID id = Ids.newId();
        dsl.insertInto(GIG_EDIT)
                .set(GIG_EDIT.ID, id)
                .set(GIG_EDIT.GIG_ID, gigId)
                .set(GIG_EDIT.USER_ID, userId)
                .set(GIG_EDIT.FIELD, field)
                .set(GIG_EDIT.OLD_VALUE, oldValue)
                .set(GIG_EDIT.NEW_VALUE, newValue)
                .set(GIG_EDIT.CREATED_AT, now.atOffset(ZoneOffset.UTC))
                .set(GIG_EDIT.UNDOES_EDIT_ID, undoesEditId)
                .execute();
        return id;
    }

    /**
     * Takes the act off the bill and closes the gap in the billing order; when it headlined, the
     * next act headlines. Expected index: the gig_artist primary key (gig first).
     */
    boolean removeAct(UUID gigId, UUID artistId) {
        int removed = dsl.deleteFrom(GIG_ARTIST)
                .where(GIG_ARTIST.GIG_ID.eq(gigId))
                .and(GIG_ARTIST.ARTIST_ID.eq(artistId))
                .execute();
        List<UUID> rest = dsl.select(GIG_ARTIST.ARTIST_ID)
                .from(GIG_ARTIST)
                .where(GIG_ARTIST.GIG_ID.eq(gigId))
                .orderBy(GIG_ARTIST.BILLING_ORDER)
                .fetch(GIG_ARTIST.ARTIST_ID);
        for (short order = 0; order < rest.size(); order++) {
            dsl.update(GIG_ARTIST)
                    .set(GIG_ARTIST.BILLING_ORDER, order)
                    .where(GIG_ARTIST.GIG_ID.eq(gigId))
                    .and(GIG_ARTIST.ARTIST_ID.eq(rest.get(order)))
                    .execute();
        }
        return removed > 0;
    }

    void markGigAddedBy(UUID gigId, UUID userId) {
        dsl.update(GIG).set(GIG.CREATED_BY, userId).where(GIG.ID.eq(gigId)).execute();
    }

    void markVenueAddedBy(UUID venueId, UUID userId) {
        dsl.update(VENUE)
                .set(VENUE.CREATED_BY, userId)
                .where(VENUE.ID.eq(venueId))
                .execute();
    }

    void markArtistAddedBy(UUID artistId, UUID userId) {
        dsl.update(ARTIST)
                .set(ARTIST.CREATED_BY, userId)
                .where(ARTIST.ID.eq(artistId))
                .execute();
    }
}
