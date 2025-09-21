package app.soundcheck.social.internal;

import static app.soundcheck.shared.jooq.social.Tables.VENUE_FOLLOW;

import app.soundcheck.catalog.VenueMergeParticipant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Social's part of a venue merge: followers of the duplicate follow the survivor. A fan
 * who followed both keeps one follow, the earlier one, with its own notify level: the first choice
 * they made. No conflicts: a follow never blocks a merge.
 */
@Component
class SocialVenueMerge implements VenueMergeParticipant {

    private final DSLContext dsl;

    SocialVenueMerge(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Expected index: venue_follow_venue (venue_id). */
    @Override
    public Map<String, Long> countsAt(UUID venueId) {
        return Map.of("followers", (long) dsl.fetchCount(VENUE_FOLLOW, VENUE_FOLLOW.VENUE_ID.eq(venueId)));
    }

    @Override
    public List<String> conflicts(UUID from, UUID into) {
        return List.of();
    }

    /** One statement per step: copy every follow across (the earlier wins on a clash), then drop the old. */
    @Override
    public void moveTo(UUID from, UUID into) {
        dsl.insertInto(
                        VENUE_FOLLOW,
                        VENUE_FOLLOW.USER_ID,
                        VENUE_FOLLOW.VENUE_ID,
                        VENUE_FOLLOW.CREATED_AT,
                        VENUE_FOLLOW.NOTIFY)
                .select(dsl.select(VENUE_FOLLOW.USER_ID, DSL.val(into), VENUE_FOLLOW.CREATED_AT, VENUE_FOLLOW.NOTIFY)
                        .from(VENUE_FOLLOW)
                        .where(VENUE_FOLLOW.VENUE_ID.eq(from)))
                .onConflict(VENUE_FOLLOW.USER_ID, VENUE_FOLLOW.VENUE_ID)
                .doUpdate()
                .set(VENUE_FOLLOW.CREATED_AT, DSL.excluded(VENUE_FOLLOW.CREATED_AT))
                .set(VENUE_FOLLOW.NOTIFY, DSL.excluded(VENUE_FOLLOW.NOTIFY))
                .where(DSL.excluded(VENUE_FOLLOW.CREATED_AT).lt(VENUE_FOLLOW.CREATED_AT))
                .execute();
        dsl.deleteFrom(VENUE_FOLLOW).where(VENUE_FOLLOW.VENUE_ID.eq(from)).execute();
    }
}
