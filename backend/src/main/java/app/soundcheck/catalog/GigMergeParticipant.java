package app.soundcheck.catalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A module that keeps rows pointing at a gig, and moves them when the operator merges a duplicate gig
 * into the real one. Catalog declares this interface and calls every implementation
 * inside its own merge transaction; content (posts, gig comments), social (alert records) and ingestion
 * (match reviews) implement it, the same inversion as {@link GigClips}. Catalog can't depend on those
 * modules, but they already depend on catalog.
 *
 * <p>Why not a {@code GigsMerged} event that each module handles later: a listener that failed after
 * catalog had committed (a post that breaks {@code post_author_gig_day}, say) would leave a half-merged
 * gig. Here a conflict, or any failure, rolls the whole merge back.
 */
public interface GigMergeParticipant {

    /** {@link #countsAt} key for posts (any status: each one blocks deleting the gig). */
    String POSTS = "posts";

    /** {@link #countsAt} key for gig comments that aren't deleted (deleting the gig would lose them). */
    String GIG_COMMENTS = "gigComments";

    /**
     * How many rows of each kind this module keeps for the gig, by a stable key ({@link #POSTS} → 3).
     * The merge preview shows them as "will move"; deleting a fan-added gig reads the content keys.
     */
    Map<String, Long> countsAt(UUID gigId);

    /**
     * Why {@code from} can't be merged into {@code into}, one plain sentence each ("@sam has a post at
     * both gigs on 2026-10-04"); empty when nothing stands in the way. Any sentence refuses the merge.
     */
    List<String> conflicts(UUID from, UUID into);

    /**
     * Re-points this module's rows from {@code from} to {@code into}. Called inside catalog's merge
     * transaction, after {@link #conflicts} came back empty and with both gigs locked.
     */
    void moveTo(UUID from, UUID into);
}
