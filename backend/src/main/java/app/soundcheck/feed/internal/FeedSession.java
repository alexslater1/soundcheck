package app.soundcheck.feed.internal;

import app.soundcheck.feed.internal.ranking.CandidateSource;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A built feed: the ranked list one user pages through, with the score and the exact
 * features of every item. Paging reads from here, so the order cannot change between pages, and what is
 * logged as served is what was scored.
 */
public record FeedSession(UUID id, UUID userId, UUID configId, Instant createdAt, Instant expiresAt, List<Item> items) {

    /**
     * @param features    the feature vector the score was computed from, in schema order
     * @param exploration true when the item is there by exploration, not by its score
     */
    public record Item(
            UUID postId, double score, double[] features, boolean exploration, Set<CandidateSource> sources) {}

    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
