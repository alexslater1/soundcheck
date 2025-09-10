package app.soundcheck.catalog.internal;

import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ATTENDANCE;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ATTENDANCE_MISSED;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_EDIT;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_REDIRECT;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_SOURCE;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * SQL for catalog's own part of a gig merge: the locks, the rows catalog owns (marks,
 * acts seen, the bill, listings, edits), the redirect, and the delete. Each statement moves every row of
 * one table in one go; nothing is read into Java and written back.
 */
@Repository
class GigMergeRepository {

    private final DSLContext dsl;

    GigMergeRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Locks both gigs ({@code for update}) in id order, and returns the ids that exist. Every merge takes
     * its two locks lowest id first, so two merges of the same pair, either way round, queue instead of
     * deadlocking. Postgres applies the row locks after the sort. Expected index: the gig primary key.
     */
    List<UUID> lock(UUID a, UUID b) {
        return dsl.select(GIG.ID)
                .from(GIG)
                .where(GIG.ID.in(a, b))
                .orderBy(GIG.ID)
                .forUpdate()
                .fetch(GIG.ID);
    }

    /** The gig's listings, primary first. Expected index: gig_source_gig (gig_id). */
    List<GigMerges.Listing> listings(UUID gigId) {
        return dsl.select(GIG_SOURCE.SOURCE, GIG_SOURCE.EXTERNAL_ID, GIG_SOURCE.URL, GIG_SOURCE.PRIMARY_LISTING)
                .from(GIG_SOURCE)
                .where(GIG_SOURCE.GIG_ID.eq(gigId))
                .orderBy(GIG_SOURCE.PRIMARY_LISTING.desc(), GIG_SOURCE.SOURCE, GIG_SOURCE.EXTERNAL_ID)
                .fetch(r -> new GigMerges.Listing(r.value1(), r.value2(), r.value3(), r.value4()));
    }

    /** What catalog keeps for the gig, by kind, for the merge preview. Each count uses a gig_id index. */
    Map<String, Long> countsAt(UUID gigId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("attendance", (long) dsl.fetchCount(GIG_ATTENDANCE, GIG_ATTENDANCE.GIG_ID.eq(gigId)));
        counts.put("acts", (long) dsl.fetchCount(GIG_ARTIST, GIG_ARTIST.GIG_ID.eq(gigId)));
        counts.put("listings", (long) dsl.fetchCount(GIG_SOURCE, GIG_SOURCE.GIG_ID.eq(gigId)));
        counts.put("edits", (long) dsl.fetchCount(GIG_EDIT, GIG_EDIT.GIG_ID.eq(gigId)));
        return counts;
    }

    /**
     * Copies each fan's mark from {@code from} to {@code into}. Where they marked both, the stronger
     * status stays (WENT &gt; GOING &gt; INTERESTED) and a USER source beats POST: the fan said so
     * themselves on one of the two. Expected index: gig_attendance_gig (gig_id, status) for the
     * read, the primary key (user_id, gig_id) for the upsert.
     */
    void mergeAttendance(UUID from, UUID into, Instant now) {
        var a = GIG_ATTENDANCE;
        dsl.insertInto(a, a.USER_ID, a.GIG_ID, a.STATUS, a.SOURCE, a.CREATED_AT, a.UPDATED_AT)
                .select(dsl.select(a.USER_ID, DSL.val(into), a.STATUS, a.SOURCE, a.CREATED_AT, DSL.val(at(now)))
                        .from(a)
                        .where(a.GIG_ID.eq(from)))
                .onConflict(a.USER_ID, a.GIG_ID)
                .doUpdate()
                .set(
                        a.STATUS,
                        DSL.when(strength(DSL.excluded(a.STATUS)).gt(strength(a.STATUS)), DSL.excluded(a.STATUS))
                                .otherwise(a.STATUS))
                .set(
                        a.SOURCE,
                        DSL.when(DSL.excluded(a.SOURCE).eq("USER"), DSL.inline("USER"))
                                .otherwise(a.SOURCE))
                .set(a.CREATED_AT, DSL.least(a.CREATED_AT, DSL.excluded(a.CREATED_AT)))
                .set(a.UPDATED_AT, DSL.excluded(a.UPDATED_AT))
                .execute();
    }

    /** WENT 3, GOING 2, INTERESTED 1. */
    private static Field<Integer> strength(Field<String> status) {
        return DSL.when(status.eq("WENT"), DSL.inline(3))
                .when(status.eq("GOING"), DSL.inline(2))
                .otherwise(DSL.inline(1));
    }

    /**
     * The acts each fan said they missed move with their mark, so this runs after {@link
     * #mergeAttendance}: the row's foreign key needs the fan's mark at {@code into} first. Someone who
     * marked both keeps the acts missed at either. Then {@code from}'s marks go, and their missed acts
     * with them (the cascade). No index leads with gig_id here (the primary key is user first): a scan of
     * the exceptions table, which holds only unticked acts.
     */
    void moveMissedAndDropAttendance(UUID from, UUID into) {
        var m = GIG_ATTENDANCE_MISSED;
        dsl.insertInto(m, m.USER_ID, m.GIG_ID, m.ARTIST_ID, m.CREATED_AT)
                .select(dsl.select(m.USER_ID, DSL.val(into), m.ARTIST_ID, m.CREATED_AT)
                        .from(m)
                        .where(m.GIG_ID.eq(from)))
                .onConflictDoNothing()
                .execute();
        dsl.deleteFrom(GIG_ATTENDANCE).where(GIG_ATTENDANCE.GIG_ID.eq(from)).execute();
    }

    /**
     * The bill becomes the union: {@code into}'s acts in their order, then {@code from}'s other acts in
     * theirs, each keeping who added it. Expected index: the gig_artist primary key (gig first).
     */
    void mergeActs(UUID from, UUID into) {
        var f = GIG_ARTIST.as("f");
        var t = GIG_ARTIST.as("t");
        Field<Short> last = dsl.select(DSL.coalesce(DSL.max(t.BILLING_ORDER), DSL.inline((short) -1)))
                .from(t)
                .where(t.GIG_ID.eq(into))
                .asField();
        Field<Short> order = last.plus(DSL.rowNumber().over().orderBy(f.BILLING_ORDER, f.ARTIST_ID))
                .cast(Short.class);
        dsl.insertInto(
                        GIG_ARTIST,
                        GIG_ARTIST.GIG_ID,
                        GIG_ARTIST.ARTIST_ID,
                        GIG_ARTIST.BILLING_ORDER,
                        GIG_ARTIST.ADDED_BY)
                .select(dsl.select(DSL.val(into), f.ARTIST_ID, order, f.ADDED_BY)
                        .from(f)
                        .where(f.GIG_ID.eq(from))
                        .andNotExists(
                                DSL.selectOne().from(t).where(t.GIG_ID.eq(into)).and(t.ARTIST_ID.eq(f.ARTIST_ID))))
                .execute();
        dsl.deleteFrom(GIG_ARTIST).where(GIG_ARTIST.GIG_ID.eq(from)).execute();
    }

    /**
     * Every listing moves, so ingestion's next run maps it to {@code into} (gig_source is keyed by source
     * and external id) instead of recreating the duplicate. A gig has one primary listing ({@code
     * gig_source_one_primary}, V007): when {@code into} has one, {@code from}'s is demoted first.
     * Expected index: gig_source_gig (gig_id).
     */
    void moveSources(UUID from, UUID into) {
        boolean intoHasPrimary =
                dsl.fetchExists(GIG_SOURCE, GIG_SOURCE.GIG_ID.eq(into).and(GIG_SOURCE.PRIMARY_LISTING.isTrue()));
        if (intoHasPrimary) {
            dsl.update(GIG_SOURCE)
                    .set(GIG_SOURCE.PRIMARY_LISTING, false)
                    .where(GIG_SOURCE.GIG_ID.eq(from))
                    .and(GIG_SOURCE.PRIMARY_LISTING.isTrue())
                    .execute();
        }
        dsl.update(GIG_SOURCE)
                .set(GIG_SOURCE.GIG_ID, into)
                .where(GIG_SOURCE.GIG_ID.eq(from))
                .execute();
    }

    /** {@code from}'s edit history joins {@code into}'s. Expected index: gig_edit_gig (gig_id, created_at). */
    void moveEdits(UUID from, UUID into) {
        dsl.update(GIG_EDIT)
                .set(GIG_EDIT.GIG_ID, into)
                .where(GIG_EDIT.GIG_ID.eq(from))
                .execute();
    }

    /**
     * Records that {@code from} now leads to {@code into}. Older redirects to {@code from} are re-pointed
     * first (A was merged into B; now B merges into C: A leads to C), since deleting {@code from} would
     * otherwise cascade them away. Expected index: gig_redirect_into.
     */
    void redirect(UUID from, UUID into, UUID adminId, Instant now) {
        dsl.update(GIG_REDIRECT)
                .set(GIG_REDIRECT.INTO_GIG_ID, into)
                .where(GIG_REDIRECT.INTO_GIG_ID.eq(from))
                .execute();
        dsl.insertInto(GIG_REDIRECT)
                .set(GIG_REDIRECT.FROM_GIG_ID, from)
                .set(GIG_REDIRECT.INTO_GIG_ID, into)
                .set(GIG_REDIRECT.MERGED_AT, at(now))
                .set(GIG_REDIRECT.MERGED_BY, adminId)
                .execute();
    }

    /**
     * Deletes the gig. After a merge every row pointing at it has moved, so the cascades find nothing;
     * {@code content.post}'s foreign key has no cascade, so a post left behind fails the delete (and
     * rolls the merge back) rather than vanishing.
     */
    void deleteGig(UUID gigId) {
        dsl.deleteFrom(GIG).where(GIG.ID.eq(gigId)).execute();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
