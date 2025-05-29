package app.soundcheck.feed.internal.ranking;

import static app.soundcheck.feed.internal.ranking.RankingFixtures.SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.feed.Reason;
import app.soundcheck.feed.internal.ranking.ReasonMapper.Context;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** "Why am I seeing this?": the top two positive contributions, in words. */
class ReasonMapperTest {

    static final Context CONTEXT = new Context("maya", "Fontaines D.C.", "WENT", 3);

    @Test
    void theTwoLargestPositiveContributionsBecomeTheReasonsLargestFirst() {
        double[] contributions = contributions(
                "follows_author", 1.6, "follows_artist", 1.2, "gig_relation", 1.4, "recency", 1.5, "seen", -2.5);

        List<Reason> reasons =
                ReasonMapper.reasons(SCHEMA, contributions, Set.of(CandidateSource.FOLLOWING), false, CONTEXT);

        // recency (1.5) is larger than gig_relation but is not something to tell a person.
        assertThat(reasons)
                .containsExactly(
                        new Reason("FOLLOWS_AUTHOR", "You follow @maya"),
                        new Reason("WENT_TO_GIG", "From a gig you went to"));
    }

    @Test
    void eachFeatureHasItsSentence() {
        assertThat(only("follows_artist", CONTEXT))
                .isEqualTo(new Reason("FOLLOWS_ARTIST", "Because you follow Fontaines D.C."));
        assertThat(only("genre_similarity", CONTEXT))
                .isEqualTo(new Reason("GENRE_MATCH", "Matches the music you like"));
        assertThat(only("artist_affinity", CONTEXT).kind()).isEqualTo("GENRE_MATCH");
        assertThat(only("proximity", CONTEXT)).isEqualTo(new Reason("NEARBY", "Popular at gigs near you"));
        // engagement_quality is only "trending" for a post the TRENDING generator found; every post has
        // some of it from the global prior.
        assertThat(ReasonMapper.reasons(
                        SCHEMA,
                        contributions("engagement_quality", 1.0),
                        Set.of(CandidateSource.TRENDING),
                        false,
                        CONTEXT))
                .containsExactly(new Reason("TRENDING", "Trending this week"));
        assertThat(ReasonMapper.reasons(
                        SCHEMA,
                        contributions("engagement_quality", 1.0, "follows_artist", 0.5),
                        Set.of(CandidateSource.ARTIST),
                        false,
                        CONTEXT))
                .containsExactly(new Reason("FOLLOWS_ARTIST", "Because you follow Fontaines D.C."));
        assertThat(only("social_proof", CONTEXT))
                .isEqualTo(new Reason("FRIENDS_ENGAGED", "Liked by 3 people you follow"));
        assertThat(only("social_proof", new Context("maya", null, null, 1)).text())
                .isEqualTo("Liked by someone you follow");
        assertThat(only("gig_relation", new Context("maya", null, "GOING", 0)))
                .isEqualTo(new Reason("GOING_TO_GIG", "From a gig you're going to"));
        assertThat(only("gig_relation", new Context("maya", null, "INTERESTED", 0)))
                .isEqualTo(new Reason("GOING_TO_GIG", "From a gig you're interested in"));
    }

    @Test
    void twoFeaturesWithTheSameKindGiveOneReason() {
        double[] contributions = contributions("artist_affinity", 0.9, "genre_similarity", 0.8, "proximity", 0.3);

        assertThat(ReasonMapper.reasons(SCHEMA, contributions, Set.of(), false, CONTEXT))
                .extracting(Reason::kind)
                .containsExactly("GENRE_MATCH", "NEARBY");
    }

    @Test
    void withNoTellingContributionTheCandidateSourceIsTheReason() {
        double[] onlyRecency = contributions("recency", 1.2);

        assertThat(ReasonMapper.reasons(SCHEMA, onlyRecency, Set.of(CandidateSource.TRENDING), false, CONTEXT))
                .containsExactly(new Reason("TRENDING", "Trending this week"));
        assertThat(ReasonMapper.reasons(SCHEMA, onlyRecency, Set.of(CandidateSource.LOCAL), false, CONTEXT))
                .containsExactly(new Reason("NEARBY", "Popular at gigs near you"));
        assertThat(ReasonMapper.reasons(SCHEMA, onlyRecency, Set.of(CandidateSource.TASTE), false, CONTEXT))
                .containsExactly(new Reason("GENRE_MATCH", "Matches the music you like"));
        assertThat(ReasonMapper.reasons(SCHEMA, onlyRecency, Set.of(CandidateSource.FRESH), false, CONTEXT))
                .containsExactly(new Reason("NEW_FOR_YOU", "New for you"));
    }

    @Test
    void anExplorationItemIsOnlyEverNewForYou() {
        double[] contributions = contributions("follows_author", 1.6, "gig_relation", 1.4);

        assertThat(ReasonMapper.reasons(SCHEMA, contributions, Set.of(CandidateSource.FRESH), true, CONTEXT))
                .containsExactly(new Reason("NEW_FOR_YOU", "New for you"));
    }

    private static Reason only(String feature, Context context) {
        return ReasonMapper.reasons(SCHEMA, contributions(feature, 1.0), Set.of(), false, context)
                .getFirst();
    }

    /** name, value, name, value, … */
    private static double[] contributions(Object... pairs) {
        double[] contributions = new double[SCHEMA.size()];
        for (int i = 0; i < pairs.length; i += 2) {
            contributions[SCHEMA.indexOf((String) pairs[i])] = (Double) pairs[i + 1];
        }
        return contributions;
    }
}
