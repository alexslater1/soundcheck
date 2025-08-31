package app.soundcheck.feed.internal;

import app.soundcheck.feed.internal.CandidateGenerator.Featured;
import app.soundcheck.feed.internal.CandidateRepository.Candidate;
import app.soundcheck.feed.internal.ranking.CandidateSource;
import app.soundcheck.feed.internal.ranking.ExplorationInjector;
import app.soundcheck.feed.internal.ranking.ExplorationInjector.Slot;
import app.soundcheck.feed.internal.ranking.FeatureVector;
import app.soundcheck.feed.internal.ranking.MmrReranker;
import app.soundcheck.feed.internal.ranking.RankingConfig;
import app.soundcheck.feed.internal.ranking.Scorer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The ranking steps of a feed session: score every candidate, diversify the order
 * (MMR, spacing, the per-author cap), then hand some slots to exploration. A pure function of its inputs:
 * no database, no clock, no writes.
 *
 * <p>The live feed ({@link HomeFeedService}) calls it and stores the result as a session; the console's
 * preview calls it and stores nothing. Because both run this one method, the playground cannot show an
 * order the real feed would not produce for the same inputs and seed.
 */
public final class FeedRanker {

    /**
     * One item of the ranked list.
     *
     * @param scored      the logit, the score and each feature's contribution under the config
     * @param exploration true when exploration put it there rather than its score
     */
    public record Ranked(
            UUID postId,
            UUID authorId,
            FeatureVector features,
            Scorer.Scored scored,
            Set<CandidateSource> sources,
            boolean exploration) {

        public double score() {
            return scored.score();
        }
    }

    /** Runs one step; the live feed passes one that times it ({@code soundcheck.feed.stage}). */
    @FunctionalInterface
    public interface Stages {
        <T> T run(String name, Supplier<T> step);

        /** Runs each step as it is, untimed. */
        Stages NONE = new Stages() {
            @Override
            public <T> T run(String name, Supplier<T> step) {
                return step.get();
            }
        };
    }

    private FeedRanker() {}

    /**
     * Ranks the candidates under the config.
     *
     * @param featured        the candidates with their features (computed with the config's
     *                        {@code halfLifeHours} and {@code priorStrength})
     * @param explorationSeed seeds the exploration draws; null leaves exploration out (the console's
     *                        default, so a weight change is not mixed up with random injection)
     * @return at most {@code sessionSize} items, best first
     */
    public static List<Ranked> rank(
            List<Featured> featured, RankingConfig config, @Nullable Long explorationSeed, Stages stages) {
        RankingConfig.Params params = config.params();

        Map<UUID, Ranked> byPost = new HashMap<>();
        List<MmrReranker.Item> items = stages.run("score", () -> {
            List<MmrReranker.Item> list = new ArrayList<>(featured.size());
            for (Featured f : featured) {
                Scorer.Scored scored = Scorer.score(config, f.features());
                Candidate c = f.candidate();
                byPost.put(c.postId(), new Ranked(c.postId(), c.authorId(), f.features(), scored, c.sources(), false));
                list.add(new MmrReranker.Item(
                        c.postId(),
                        scored.score(),
                        c.authorId(),
                        Set.copyOf(f.facts().artistIds()),
                        c.gigId()));
            }
            return list;
        });
        List<MmrReranker.Item> diversified = stages.run(
                "rerank",
                () -> MmrReranker.rerank(
                        items, params.diversityLambda(), params.sessionSize(), params.authorCapPerSession()));

        if (explorationSeed == null) {
            return diversified.stream().map(item -> byPost.get(item.postId())).toList();
        }
        List<MmrReranker.Item> fresh = items.stream()
                .filter(item -> byPost.get(item.postId()).sources().contains(CandidateSource.FRESH))
                .toList();
        List<Slot<MmrReranker.Item>> slots = stages.run(
                "explore",
                () -> ExplorationInjector.inject(
                        diversified,
                        fresh,
                        MmrReranker.Item::postId,
                        params.explorationRate(),
                        params.sessionSize(),
                        explorationSeed));
        return slots.stream()
                .map(slot -> {
                    Ranked ranked = byPost.get(slot.item().postId());
                    return slot.exploration()
                            ? new Ranked(
                                    ranked.postId(),
                                    ranked.authorId(),
                                    ranked.features(),
                                    ranked.scored(),
                                    ranked.sources(),
                                    true)
                            : ranked;
                })
                .toList();
    }

    /** The exploration seed of a live session: its id, so the same session can be rebuilt exactly. */
    public static long seedOf(UUID sessionId) {
        return sessionId.getMostSignificantBits() ^ sessionId.getLeastSignificantBits();
    }
}
