package app.soundcheck.feed.internal.ranking;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The twelve ranking features of one post for one viewer, from facts that were loaded
 * beforehand. A pure function: no database, no clock, so every formula has a unit test.
 */
public final class FeatureComputer {

    /** An explicit follow counts as at least this much affinity. */
    static final double FOLLOW_FLOOR = 0.5;

    /** What is known about the viewer, as far as the features need it. */
    public record Viewer(
            Set<UUID> followedUsers,
            Set<UUID> followedArtists,
            Map<UUID, String> attendance,
            Map<Short, Double> genreVector,
            Set<UUID> seenPosts,
            Map<UUID, Double> artistAffinity,
            Map<UUID, Double> authorAffinity) {}

    /**
     * What is known about the post.
     *
     * @param genreVector    the mean of the tagged artists' genre weights
     * @param distanceKm     from the viewer's home to the gig's venue; null when either is unknown
     * @param friendsEngaged followed users who liked or commented
     */
    public record PostFacts(
            UUID postId,
            UUID authorId,
            @Nullable UUID gigId,
            Instant publishedAt,
            List<UUID> artistIds,
            Map<Short, Double> genreVector,
            long impressions,
            long likes,
            long comments,
            long shares,
            long completes,
            @Nullable Double distanceKm,
            int friendsEngaged) {}

    /**
     * The global engagement prior and the parameters the formulas use.
     *
     * @param meanRate {@code m}, the global mean engagement rate
     * @param p99Rate  the 99th percentile of the smoothed rate, the scale of feature 8
     */
    public record Scales(double halfLifeHours, double priorStrength, double meanRate, double p99Rate) {}

    private FeatureComputer() {}

    public static FeatureVector compute(
            FeatureSchema schema, Viewer viewer, PostFacts post, Scales scales, Instant now) {
        boolean followsAuthor = viewer.followedUsers().contains(post.authorId());
        boolean followsArtist = post.artistIds().stream().anyMatch(viewer.followedArtists()::contains);
        return FeatureVector.builder(schema)
                .set("follows_author", followsAuthor ? 1 : 0)
                .set("follows_artist", followsArtist ? 1 : 0)
                .set("artist_affinity", artistAffinity(viewer, post))
                .set(
                        "author_affinity",
                        Math.max(
                                viewer.authorAffinity().getOrDefault(post.authorId(), 0.0),
                                followsAuthor ? FOLLOW_FLOOR : 0))
                .set("genre_similarity", GenreProfile.cosine(viewer.genreVector(), post.genreVector()))
                .set(
                        "gig_relation",
                        gigRelation(
                                post.gigId() == null
                                        ? null
                                        : viewer.attendance().get(post.gigId())))
                .set("proximity", proximity(post.distanceKm()))
                .set("recency", recency(post.publishedAt(), now, scales.halfLifeHours()))
                .set("engagement_quality", engagementQuality(post, scales))
                .set("social_proof", socialProof(post.friendsEngaged()))
                .set("seen", viewer.seenPosts().contains(post.postId()) ? 1 : 0)
                // position_prior stays 0 at serve time: only the lab sets it, to absorb position bias.
                .build();
    }

    /** The strongest affinity among the tagged artists; a followed artist counts as at least 0.5. */
    static double artistAffinity(Viewer viewer, PostFacts post) {
        double max = 0;
        for (UUID artist : post.artistIds()) {
            double affinity = viewer.artistAffinity().getOrDefault(artist, 0.0);
            if (viewer.followedArtists().contains(artist)) {
                affinity = Math.max(affinity, FOLLOW_FLOOR);
            }
            max = Math.max(max, affinity);
        }
        return max;
    }

    /** WENT = 1.0, GOING = 0.8, INTERESTED = 0.5, else 0. */
    static double gigRelation(@Nullable String attendance) {
        if (attendance == null) {
            return 0;
        }
        return switch (attendance) {
            case "WENT" -> 1.0;
            case "GOING" -> 0.8;
            case "INTERESTED" -> 0.5;
            default -> 0;
        };
    }

    /** {@code exp(-d / 25)}: 1 at the venue's door, about 0.37 at 25 km, 0 when the distance is unknown. */
    static double proximity(@Nullable Double distanceKm) {
        return distanceKm == null ? 0 : Math.exp(-Math.max(0, distanceKm) / 25.0);
    }

    /** {@code 2^(-ageHours / halfLifeHours)}: 1 when just published, 0.5 after one half-life. */
    static double recency(Instant publishedAt, Instant now, double halfLifeHours) {
        double ageHours = Math.max(0, Duration.between(publishedAt, now).toMillis() / 3_600_000.0);
        return Math.pow(2, -ageHours / halfLifeHours);
    }

    /**
     * Bayesian-smoothed engagement rate, scaled by the global 99th percentile:
     * {@code (likes + 2·comments + shares + completes + k·m) / (impressions + k)}. The {@code k}
     * pseudo-impressions at the global mean rate {@code m} keep a post with three impressions and one
     * like from looking like the best post there is. 0 until a prior exists.
     */
    static double engagementQuality(PostFacts post, Scales scales) {
        if (scales.p99Rate() <= 0) {
            return 0;
        }
        double engagement = post.likes() + 2.0 * post.comments() + post.shares() + post.completes();
        double smoothed = (engagement + scales.priorStrength() * scales.meanRate())
                / (post.impressions() + scales.priorStrength());
        return Math.min(1, smoothed / scales.p99Rate());
    }

    /** {@code 1 - exp(-n / 2)}: 0.39 for one friend, 0.63 for two, levelling off towards 1. */
    static double socialProof(int friendsEngaged) {
        return 1 - Math.exp(-Math.max(0, friendsEngaged) / 2.0);
    }
}
