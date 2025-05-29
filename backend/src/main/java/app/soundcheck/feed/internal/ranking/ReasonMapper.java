package app.soundcheck.feed.internal.ranking;

import app.soundcheck.feed.Reason;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/**
 * "Why am I seeing this?": the two features that pushed the score up the most, said in
 * words. An exploration item has one reason only, "New for you": it was not chosen for its score.
 */
public final class ReasonMapper {

    static final int MAX_REASONS = 2;

    /**
     * What the sentences need beyond the numbers.
     *
     * @param authorHandle       the post's author
     * @param followedArtistName a tagged artist the viewer follows, if any
     * @param attendance         the viewer's relation to the post's gig: WENT, GOING, INTERESTED or null
     * @param friendsEngaged     how many people the viewer follows liked or commented on the post
     */
    public record Context(
            String authorHandle,
            @Nullable String followedArtistName,
            @Nullable String attendance,
            int friendsEngaged) {}

    private ReasonMapper() {}

    public static List<Reason> reasons(
            FeatureSchema schema,
            double[] contributions,
            Set<CandidateSource> sources,
            boolean exploration,
            Context context) {
        if (exploration) {
            return List.of(new Reason("NEW_FOR_YOU", "New for you"));
        }
        List<Reason> reasons = new ArrayList<>();
        IntStream.range(0, contributions.length)
                .filter(i -> contributions[i] > 0)
                .boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> contributions[i])
                        .reversed())
                .forEach(i -> {
                    Reason reason = forFeature(schema.names().get(i), context, sources);
                    if (reason != null
                            && reasons.size() < MAX_REASONS
                            && reasons.stream().noneMatch(r -> r.kind().equals(reason.kind()))) {
                        reasons.add(reason);
                    }
                });
        if (reasons.isEmpty()) {
            reasons.add(forSources(sources));
        }
        return reasons;
    }

    /** The sentence for a feature that contributed; null for features that are not a reason to a person. */
    private static @Nullable Reason forFeature(String feature, Context context, Set<CandidateSource> sources) {
        return switch (feature) {
            case "follows_author" -> new Reason("FOLLOWS_AUTHOR", "You follow @" + context.authorHandle());
            case "follows_artist" ->
                context.followedArtistName() == null
                        ? null
                        : new Reason("FOLLOWS_ARTIST", "Because you follow " + context.followedArtistName());
            case "artist_affinity", "genre_similarity" -> new Reason("GENRE_MATCH", "Matches the music you like");
            case "gig_relation" ->
                switch (context.attendance() == null ? "" : context.attendance()) {
                    case "WENT" -> new Reason("WENT_TO_GIG", "From a gig you went to");
                    case "GOING" -> new Reason("GOING_TO_GIG", "From a gig you're going to");
                    case "INTERESTED" -> new Reason("GOING_TO_GIG", "From a gig you're interested in");
                    default -> null;
                };
            case "proximity" -> new Reason("NEARBY", "Popular at gigs near you");
            // Every post gets some engagement_quality from the global prior, even one never shown; it is
            // only "trending" when it really had engagement lately (the TRENDING generator found it).
            case "engagement_quality" ->
                sources.contains(CandidateSource.TRENDING) ? new Reason("TRENDING", "Trending this week") : null;
            case "social_proof" ->
                context.friendsEngaged() <= 0
                        ? null
                        : new Reason(
                                "FRIENDS_ENGAGED",
                                context.friendsEngaged() == 1
                                        ? "Liked by someone you follow"
                                        : "Liked by " + context.friendsEngaged() + " people you follow");
            // author_affinity, recency, seen, position_prior: true, but not something to tell a person.
            default -> null;
        };
    }

    /** When no feature gives a sentence, say where the post came from. */
    private static Reason forSources(Set<CandidateSource> sources) {
        if (sources.contains(CandidateSource.TRENDING)) {
            return new Reason("TRENDING", "Trending this week");
        }
        if (sources.contains(CandidateSource.LOCAL)) {
            return new Reason("NEARBY", "Popular at gigs near you");
        }
        if (sources.contains(CandidateSource.TASTE)) {
            return new Reason("GENRE_MATCH", "Matches the music you like");
        }
        return new Reason("NEW_FOR_YOU", "New for you");
    }
}
