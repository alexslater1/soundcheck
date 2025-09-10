package app.soundcheck.social.internal;

import static app.soundcheck.shared.jooq.social.Tables.GIG_ALERT_SENT;

import app.soundcheck.catalog.GigMergeParticipant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Social's part of a gig merge: the new-gig alert records ({@code gig_alert_sent}) move
 * to the surviving gig, so nobody is alerted about it again. Someone alerted about both gigs keeps one
 * row: the earlier one, the first time they heard. No conflicts: an alert record never blocks a merge.
 */
@Component
class SocialGigMerge implements GigMergeParticipant {

    private final DSLContext dsl;

    SocialGigMerge(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * No index leads with gig_id (the primary key is user first): a scan, of a table the alert pass keeps
     * small by deleting the rows of gigs that have started.
     */
    @Override
    public Map<String, Long> countsAt(UUID gigId) {
        return Map.of("alertsSent", (long) dsl.fetchCount(GIG_ALERT_SENT, GIG_ALERT_SENT.GIG_ID.eq(gigId)));
    }

    @Override
    public List<String> conflicts(UUID from, UUID into) {
        return List.of();
    }

    @Override
    public void moveTo(UUID from, UUID into) {
        dsl.insertInto(
                        GIG_ALERT_SENT,
                        GIG_ALERT_SENT.USER_ID,
                        GIG_ALERT_SENT.GIG_ID,
                        GIG_ALERT_SENT.STARTS_AT,
                        GIG_ALERT_SENT.SENT_AT)
                .select(dsl.select(
                                GIG_ALERT_SENT.USER_ID, DSL.val(into), GIG_ALERT_SENT.STARTS_AT, GIG_ALERT_SENT.SENT_AT)
                        .from(GIG_ALERT_SENT)
                        .where(GIG_ALERT_SENT.GIG_ID.eq(from)))
                .onConflict(GIG_ALERT_SENT.USER_ID, GIG_ALERT_SENT.GIG_ID)
                .doUpdate()
                .set(GIG_ALERT_SENT.STARTS_AT, DSL.excluded(GIG_ALERT_SENT.STARTS_AT))
                .set(GIG_ALERT_SENT.SENT_AT, DSL.excluded(GIG_ALERT_SENT.SENT_AT))
                .where(DSL.excluded(GIG_ALERT_SENT.SENT_AT).lt(GIG_ALERT_SENT.SENT_AT))
                .execute();
        dsl.deleteFrom(GIG_ALERT_SENT).where(GIG_ALERT_SENT.GIG_ID.eq(from)).execute();
    }
}
