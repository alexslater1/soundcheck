package app.soundcheck.ingestion.internal.resolve;

import static app.soundcheck.shared.jooq.ingestion.Tables.MATCH_REVIEW;

import app.soundcheck.shared.Ids;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * {@code ingestion.match_review}: listings that almost matched an existing gig and were kept apart.
 * One row per (listing, candidate) pair, however many runs see it; the console decides.
 */
@Repository
public class MatchReviews {

    private final org.jooq.DSLContext dsl;

    public MatchReviews(org.jooq.DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Records the pair once; later runs that see it again change nothing. */
    public void record(
            String source,
            String externalId,
            UUID gigId,
            UUID candidateGigId,
            String title,
            String candidateTitle,
            GigMatcher.Decision decision) {
        dsl.insertInto(MATCH_REVIEW)
                .set(MATCH_REVIEW.ID, Ids.newId())
                .set(MATCH_REVIEW.SOURCE, source)
                .set(MATCH_REVIEW.EXTERNAL_ID, externalId)
                .set(MATCH_REVIEW.GIG_ID, gigId)
                .set(MATCH_REVIEW.CANDIDATE_GIG_ID, candidateGigId)
                .set(MATCH_REVIEW.TITLE, title)
                .set(MATCH_REVIEW.CANDIDATE_TITLE, candidateTitle)
                .set(MATCH_REVIEW.TITLE_SIMILARITY, (float) decision.titleSimilarity())
                .set(MATCH_REVIEW.ACT_SIMILARITY, (float) decision.actSimilarity())
                .set(MATCH_REVIEW.REASON, decision.reason())
                .onConflictDoNothing()
                .execute();
    }
}
