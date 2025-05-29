package app.soundcheck.feed.internal.ranking;

import static app.soundcheck.feed.internal.ranking.RankingFixtures.SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.soundcheck.feed.internal.ranking.FeatureComputer.PostFacts;
import app.soundcheck.feed.internal.ranking.FeatureComputer.Scales;
import app.soundcheck.feed.internal.ranking.FeatureComputer.Viewer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The feature formulas, each on its own and all twelve together. */
class FeatureComputerTest {

    static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    static final Scales SCALES = new Scales(24, 20, 0.1, 0.5);
    static final UUID AUTHOR = UUID.randomUUID();
    static final UUID ARTIST = UUID.randomUUID();
    static final UUID OTHER_ARTIST = UUID.randomUUID();
    static final UUID GIG = UUID.randomUUID();
    static final short INDIE = 2;
    static final short JAZZ = 14;

    @Test
    void recencyHalvesWithEveryHalfLife() {
        assertThat(FeatureComputer.recency(NOW, NOW, 24)).isEqualTo(1.0);
        assertThat(FeatureComputer.recency(NOW.minus(Duration.ofHours(24)), NOW, 24))
                .isCloseTo(0.5, within(1e-12));
        assertThat(FeatureComputer.recency(NOW.minus(Duration.ofHours(12)), NOW, 24))
                .isCloseTo(1 / Math.sqrt(2), within(1e-12));
        assertThat(FeatureComputer.recency(NOW.minus(Duration.ofHours(48)), NOW, 12))
                .isCloseTo(1.0 / 16, within(1e-12));
        // A post stamped slightly in the future (clock skew) is simply new.
        assertThat(FeatureComputer.recency(NOW.plusSeconds(30), NOW, 24)).isEqualTo(1.0);
    }

    @Test
    void proximityFallsOffWithDistanceAndIsZeroWhenUnknown() {
        assertThat(FeatureComputer.proximity(0.0)).isEqualTo(1.0);
        assertThat(FeatureComputer.proximity(25.0)).isCloseTo(Math.exp(-1), within(1e-12));
        assertThat(FeatureComputer.proximity(50.0)).isCloseTo(Math.exp(-2), within(1e-12));
        assertThat(FeatureComputer.proximity(null)).isZero();
    }

    @Test
    void gigRelationRanksWentAboveGoingAboveInterested() {
        assertThat(FeatureComputer.gigRelation("WENT")).isEqualTo(1.0);
        assertThat(FeatureComputer.gigRelation("GOING")).isEqualTo(0.8);
        assertThat(FeatureComputer.gigRelation("INTERESTED")).isEqualTo(0.5);
        assertThat(FeatureComputer.gigRelation(null)).isZero();
    }

    @Test
    void socialProofGrowsWithFriendsAndLevelsOff() {
        assertThat(FeatureComputer.socialProof(0)).isZero();
        assertThat(FeatureComputer.socialProof(1)).isCloseTo(1 - Math.exp(-0.5), within(1e-12));
        assertThat(FeatureComputer.socialProof(2)).isCloseTo(1 - Math.exp(-1), within(1e-12));
        assertThat(FeatureComputer.socialProof(20)).isGreaterThan(0.9999);
    }

    @Test
    void engagementQualityIsSmoothedTowardsTheGlobalMeanAndScaledByTheNinetyNinthPercentile() {
        // Never shown: exactly the global mean, 0.1, which is 0.2 of the p99 (0.5).
        assertThat(FeatureComputer.engagementQuality(post(0, 0, 0, 0, 0), SCALES))
                .isCloseTo(0.2, within(1e-12));
        // (10 likes + 2·5 comments + 0 shares + 5 completes + 20·0.1) / (100 + 20) = 0.225 → 0.45.
        assertThat(FeatureComputer.engagementQuality(post(100, 10, 5, 0, 5), SCALES))
                .isCloseTo(0.45, within(1e-12));
        // One like on three impressions does not look like the best post there is: (1 + 2) / 23.
        assertThat(FeatureComputer.engagementQuality(post(3, 1, 0, 0, 0), SCALES))
                .isCloseTo(3.0 / 23 / 0.5, within(1e-12));
        // Above the p99: capped at 1.
        assertThat(FeatureComputer.engagementQuality(post(100, 90, 20, 10, 50), SCALES))
                .isEqualTo(1.0);
        // No prior yet: nothing to scale by.
        assertThat(FeatureComputer.engagementQuality(post(100, 10, 5, 0, 5), new Scales(24, 20, 0, 0)))
                .isZero();
    }

    @Test
    void theStrongestTaggedArtistCountsAndAFollowedArtistCountsAsAtLeastAHalf() {
        Viewer viewer = viewer(Set.of(), Set.of(ARTIST), Map.of(), Map.of(ARTIST, 0.2, OTHER_ARTIST, 0.7), Map.of());

        assertThat(FeatureComputer.artistAffinity(viewer, tagged(ARTIST))).isEqualTo(0.5);
        assertThat(FeatureComputer.artistAffinity(viewer, tagged(OTHER_ARTIST))).isEqualTo(0.7);
        assertThat(FeatureComputer.artistAffinity(viewer, tagged(ARTIST, OTHER_ARTIST)))
                .isEqualTo(0.7);
        assertThat(FeatureComputer.artistAffinity(viewer, tagged())).isZero();
    }

    @Test
    void genreVectorsAreBlendedNormalisedAndComparedByCosine() {
        Map<Short, Double> blended = GenreProfile.blend(Map.of(INDIE, 1.0), Map.of(JAZZ, 0.5));
        // 0.6·1.0 on indie, 0.4·0.5 on jazz, scaled to length 1.
        double length = Math.sqrt(0.36 + 0.04);
        assertThat(blended.get(INDIE)).isCloseTo(0.6 / length, within(1e-12));
        assertThat(blended.get(JAZZ)).isCloseTo(0.2 / length, within(1e-12));
        assertThat(GenreProfile.top(blended, 1)).containsExactly(INDIE);
        assertThat(GenreProfile.blend(Map.of(), Map.of())).isEmpty();

        Map<Short, Double> post = GenreProfile.mean(List.of(Map.of(INDIE, 1.0), Map.of(INDIE, 0.6, JAZZ, 0.4)));
        assertThat(post.get(INDIE)).isCloseTo(0.8, within(1e-12));
        assertThat(post.get(JAZZ)).isCloseTo(0.2, within(1e-12));

        assertThat(GenreProfile.cosine(Map.of(INDIE, 1.0), Map.of(INDIE, 0.3))).isCloseTo(1.0, within(1e-12));
        assertThat(GenreProfile.cosine(Map.of(INDIE, 1.0), Map.of(JAZZ, 1.0))).isZero();
        assertThat(GenreProfile.cosine(Map.of(INDIE, 1.0), post)).isCloseTo(0.8 / Math.sqrt(0.68), within(1e-12));
        assertThat(GenreProfile.cosine(Map.of(), post)).isZero();
    }

    @Test
    void allTwelveFeaturesOfOnePostInSchemaOrder() {
        UUID postId = UUID.randomUUID();
        Viewer viewer = new Viewer(
                Set.of(AUTHOR),
                Set.of(ARTIST),
                Map.of(GIG, "GOING"),
                Map.of(INDIE, 1.0),
                Set.of(postId),
                Map.of(ARTIST, 0.9),
                Map.of(AUTHOR, 0.2));
        PostFacts post = new PostFacts(
                postId,
                AUTHOR,
                GIG,
                NOW.minus(Duration.ofHours(24)),
                List.of(ARTIST),
                Map.of(INDIE, 0.8, JAZZ, 0.2),
                100,
                10,
                5,
                0,
                5,
                25.0,
                2);

        double[] features =
                FeatureComputer.compute(SCHEMA, viewer, post, SCALES, NOW).toArray();

        assertThat(features)
                .containsExactly(
                        new double[] {
                            1.0, // follows_author
                            1.0, // follows_artist
                            0.9, // artist_affinity
                            0.5, // author_affinity: 0.2, raised to the follow floor
                            0.8 / Math.sqrt(0.68), // genre_similarity
                            0.8, // gig_relation: GOING
                            Math.exp(-1), // proximity at 25 km
                            0.5, // recency after one half-life
                            0.45, // engagement_quality
                            1 - Math.exp(-1), // social_proof, two friends
                            1.0, // seen
                            0.0 // position_prior
                        },
                        within(1e-12));
    }

    @Test
    void aPostNothingIsKnownAboutHasOnlyRecencyAndTheGlobalMean() {
        Viewer nobody = viewer(Set.of(), Set.of(), Map.of(), Map.of(), Map.of());
        PostFacts post =
                new PostFacts(UUID.randomUUID(), AUTHOR, null, NOW, List.of(), Map.of(), 0, 0, 0, 0, 0, null, 0);

        double[] features =
                FeatureComputer.compute(SCHEMA, nobody, post, SCALES, NOW).toArray();

        assertThat(features).containsExactly(new double[] {0, 0, 0, 0, 0, 0, 0, 1.0, 0.2, 0, 0, 0}, within(1e-12));
    }

    private static Viewer viewer(
            Set<UUID> followedUsers,
            Set<UUID> followedArtists,
            Map<UUID, String> attendance,
            Map<UUID, Double> artistAffinity,
            Map<UUID, Double> authorAffinity) {
        return new Viewer(
                followedUsers, followedArtists, attendance, Map.of(), Set.of(), artistAffinity, authorAffinity);
    }

    private static PostFacts post(long impressions, long likes, long comments, long shares, long completes) {
        return new PostFacts(
                UUID.randomUUID(),
                AUTHOR,
                null,
                NOW,
                List.of(),
                Map.of(),
                impressions,
                likes,
                comments,
                shares,
                completes,
                null,
                0);
    }

    private static PostFacts tagged(UUID... artists) {
        return new PostFacts(UUID.randomUUID(), AUTHOR, null, NOW, List.of(artists), Map.of(), 0, 0, 0, 0, 0, null, 0);
    }
}
