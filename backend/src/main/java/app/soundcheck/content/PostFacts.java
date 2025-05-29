package app.soundcheck.content;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Content's read API for the signals module: what an interaction with a post says about its author,
 * the artists tagged in it and their genres. Signals only ever holds post ids; this turns them into the
 * things affinities are kept for.
 */
public interface PostFacts {

    /** Which of the ids are posts that exist (not deleted). */
    Set<UUID> existing(Collection<UUID> postIds);

    /** Facts about the posts that exist (not deleted) among the ids, in one pass. */
    Map<UUID, Facts> facts(Collection<UUID> postIds);

    /**
     * @param genreWeights genre id → weight: the mean of the tagged artists' genre weights,
     *     empty when no tagged artist has genres
     */
    record Facts(UUID postId, UUID authorId, List<UUID> artistIds, Map<Short, Double> genreWeights) {}
}
