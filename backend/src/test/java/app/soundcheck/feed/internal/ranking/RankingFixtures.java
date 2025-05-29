package app.soundcheck.feed.internal.ranking;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Ranking config v1, as seeded by V015, for the ranking tests. */
final class RankingFixtures {

    static final FeatureSchema SCHEMA = FeatureSchema.load();

    private RankingFixtures() {}

    static Map<String, Double> v1Weights() {
        Map<String, Double> weights = new HashMap<>();
        weights.put("bias", -2.0);
        weights.put("follows_author", 1.6);
        weights.put("follows_artist", 1.2);
        weights.put("artist_affinity", 1.0);
        weights.put("author_affinity", 0.8);
        weights.put("genre_similarity", 1.0);
        weights.put("gig_relation", 1.4);
        weights.put("proximity", 0.6);
        weights.put("recency", 1.5);
        weights.put("engagement_quality", 1.8);
        weights.put("social_proof", 0.7);
        weights.put("seen", -2.5);
        weights.put("position_prior", 0.0);
        return weights;
    }

    static Map<String, Integer> v1Caps() {
        Map<String, Integer> caps = new HashMap<>();
        caps.put("FOLLOWING", 300);
        caps.put("ARTIST", 300);
        caps.put("GIG", 200);
        caps.put("TASTE", 300);
        caps.put("LOCAL", 200);
        caps.put("TRENDING", 200);
        caps.put("FRESH", 100);
        return caps;
    }

    static RankingConfig.Params v1Params() {
        return new RankingConfig.Params(24, 20, 0.8, 0.1, 200, 10, v1Caps(), 50, 3);
    }

    static RankingConfig v1() {
        return config(v1Weights());
    }

    static RankingConfig config(Map<String, Double> weights) {
        return RankingConfig.of(UUID.randomUUID(), 1, SCHEMA.version(), weights, v1Params(), SCHEMA);
    }
}
