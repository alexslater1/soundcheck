package app.soundcheck.ingestion.internal.resolve;

import static app.soundcheck.shared.jooq.ingestion.Tables.MATCH_REVIEW;

import app.soundcheck.catalog.GigMergeParticipant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Ingestion's part of a gig merge: match reviews ({@code ingestion.match_review},
 * V031) that name the merged gig, as the listing's gig or as its candidate, name the survivor instead.
 * A review whose two sides are now the same gig has been answered by the merge itself: it becomes MERGED.
 * No conflicts: a review never blocks a merge.
 *
 * <p>The table is small (one row per close call the resolver has seen) and has no index on either gig
 * column, so these statements scan it.
 */
@Component
class MatchReviewGigMerge implements GigMergeParticipant {

    private final DSLContext dsl;
    private final Clock clock;

    MatchReviewGigMerge(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    @Override
    public Map<String, Long> countsAt(UUID gigId) {
        return Map.of("matchReviews", (long) dsl.fetchCount(
                MATCH_REVIEW, MATCH_REVIEW.GIG_ID.eq(gigId).or(MATCH_REVIEW.CANDIDATE_GIG_ID.eq(gigId))));
    }

    @Override
    public List<String> conflicts(UUID from, UUID into) {
        return List.of();
    }

    @Override
    public void moveTo(UUID from, UUID into) {
        // One question per (listing, candidate) pair: when the listing was already asked about the
        // survivor, that row stays and the one about the merged gig goes.
        var existing = MATCH_REVIEW.as("existing");
        dsl.deleteFrom(MATCH_REVIEW)
                .where(MATCH_REVIEW.CANDIDATE_GIG_ID.eq(from))
                .andExists(DSL.selectOne()
                        .from(existing)
                        .where(existing.SOURCE.eq(MATCH_REVIEW.SOURCE))
                        .and(existing.EXTERNAL_ID.eq(MATCH_REVIEW.EXTERNAL_ID))
                        .and(existing.CANDIDATE_GIG_ID.eq(into)))
                .execute();
        dsl.update(MATCH_REVIEW)
                .set(MATCH_REVIEW.CANDIDATE_GIG_ID, into)
                .where(MATCH_REVIEW.CANDIDATE_GIG_ID.eq(from))
                .execute();
        dsl.update(MATCH_REVIEW)
                .set(MATCH_REVIEW.GIG_ID, into)
                .where(MATCH_REVIEW.GIG_ID.eq(from))
                .execute();
        dsl.update(MATCH_REVIEW)
                .set(MATCH_REVIEW.STATUS, "MERGED")
                .set(MATCH_REVIEW.DECIDED_AT, clock.instant().atOffset(ZoneOffset.UTC))
                .where(MATCH_REVIEW.GIG_ID.eq(into))
                .and(MATCH_REVIEW.CANDIDATE_GIG_ID.eq(into))
                .and(MATCH_REVIEW.STATUS.ne("MERGED"))
                .execute();
    }
}
