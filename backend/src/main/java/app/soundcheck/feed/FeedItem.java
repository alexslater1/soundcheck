package app.soundcheck.feed;

import app.soundcheck.content.Post;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One item of the ranked home feed: the post, where it stands in the session, and why it is
 * there. {@code debug} is only present for an admin who asked for it.
 */
public record FeedItem(Post post, int position, List<Reason> reasons, @Schema(nullable = true) Debug debug) {

    /** The score and how it came about: one line per feature. */
    public record Debug(double score, List<Contribution> contributions) {}

    /** {@code contribution = weight × value} (in logit space). */
    public record Contribution(String feature, double value, double weight, double contribution) {}
}
