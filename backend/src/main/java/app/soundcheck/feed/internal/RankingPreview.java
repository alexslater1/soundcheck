package app.soundcheck.feed.internal;

import app.soundcheck.catalog.ArtistSummary;
import app.soundcheck.content.Post;
import app.soundcheck.content.PostFeed;
import app.soundcheck.feed.FeedItem;
import app.soundcheck.feed.internal.CandidateGenerator.Featured;
import app.soundcheck.feed.internal.CandidateRepository.Candidate;
import app.soundcheck.feed.internal.ranking.CandidateSource;
import app.soundcheck.feed.internal.ranking.FeatureComputer;
import app.soundcheck.feed.internal.ranking.FeatureSchema;
import app.soundcheck.feed.internal.ranking.RankingConfig;
import app.soundcheck.feed.internal.ranking.RankingMetrics;
import app.soundcheck.identity.Users;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.signals.EngagementPriors;
import app.soundcheck.signals.EngagementPriors.Prior;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The console's ranking preview: one user's feed as two configs would rank it, side by side,
 * without writing anything. The ranking is {@link FeedRanker}'s, the live feed's own code; only the
 * session store and the served-item log are left out.
 *
 * <p>Loading a user's context and candidates is most of the cost, and a slider move doesn't change them,
 * so they are kept for {@value #INPUT_TTL_SECONDS} seconds per user (and per the params that decide
 * them: the candidate caps, the local radius and the seen window). Within that time a preview only
 * re-scores. Two params change features rather than the candidates, {@code halfLifeHours} (recency) and
 * {@code priorStrength} (engagement quality); a config that differs in either has its features
 * recomputed from the cached facts.
 */
@Service
@ConditionalOnRole(Roles.API)
public class RankingPreview {

    static final int INPUT_TTL_SECONDS = 60;
    static final int OVERLAP_K = 10;
    /** Posts may have gone (deleted, blocked) since they were candidates; a few spare ids cover that. */
    static final int SPARE = 10;

    /**
     * Both rankings and how far they agree.
     *
     * @param b            null when only one config was asked for (the feed inspector's "Would see now")
     * @param overlapAt10  share of the top 10 the lists have in common; null without b
     * @param kendallTau   Kendall's τ-b over the items both lists show (by rank); null without b or with
     *                     fewer than two such items
     * @param commonItems  how many items τ was computed over
     * @param seed         the exploration seed both lists shared, or null when exploration was off
     * @param candidates   how many candidates the user has (before ranking)
     */
    public record Preview(
            ListView a,
            @Schema(nullable = true) ListView b,
            @Schema(nullable = true) Double overlapAt10,
            @Schema(nullable = true) Double kendallTau,
            int commonItems,
            @Schema(nullable = true) Long seed,
            int candidates) {}

    /** One config's ranked list. {@code configId} is null for an inline (unsaved) config. */
    public record ListView(@Schema(nullable = true) UUID configId, int configVersion, List<Item> items) {}

    /**
     * One ranked post.
     *
     * @param rank      1 for the top
     * @param otherRank its rank in the other list, or null when the other list doesn't show it ("NEW")
     * @param bias      the config's bias: the waterfall starts here, adds each contribution and ends at
     *                  {@code logit}, whose logistic is {@code score}
     */
    public record Item(
            int rank,
            @Schema(nullable = true) Integer otherRank,
            PostCard post,
            double score,
            double logit,
            double bias,
            List<FeedItem.Contribution> contributions,
            List<String> sources,
            boolean exploration) {}

    /** What the console shows of a post next to its rank. */
    public record PostCard(
            UUID id,
            UUID authorId,
            String authorHandle,
            List<String> artists,
            @Schema(nullable = true) String gigTitle,
            @Schema(nullable = true) String thumbUrl,
            @Schema(nullable = true) String caption,
            @Schema(nullable = true) Instant publishedAt) {}

    /** What decides a user's candidates: the cache key. */
    private record Key(UUID userId, int seenWindowDays, Map<String, Integer> caps, double localRadiusKm) {

        static Key of(UUID userId, RankingConfig.Params params) {
            return new Key(userId, params.seenWindowDays(), params.candidateCaps(), params.localRadiusKm());
        }
    }

    /** A user's context and featured candidates as loaded at {@code now}, with the params the features used. */
    private record Inputs(
            UserContext viewer,
            List<Featured> featured,
            double halfLifeHours,
            double priorStrength,
            Prior prior,
            Instant now) {}

    private final UserContextLoader contexts;
    private final CandidateGenerator generator;
    private final EngagementPriors priors;
    private final PostFeed posts;
    private final Users users;
    private final FeatureSchema schema;
    private final Clock clock;
    private final Map<Key, Inputs> cache = new ConcurrentHashMap<>();

    RankingPreview(
            UserContextLoader contexts,
            CandidateGenerator generator,
            EngagementPriors priors,
            PostFeed posts,
            Users users,
            FeatureSchema schema,
            Clock clock) {
        this.contexts = contexts;
        this.generator = generator;
        this.priors = priors;
        this.posts = posts;
        this.users = users;
        this.schema = schema;
        this.clock = clock;
    }

    /**
     * Ranks the user's candidates under {@code a} and, if given, {@code b}.
     *
     * @param limit       how many items each list shows (1–50)
     * @param exploration whether exploration fills slots, as it does live; both lists then share one seed
     */
    public Preview preview(UUID userId, RankingConfig a, @Nullable RankingConfig b, int limit, boolean exploration) {
        if (users.summaries(Set.of(userId)).isEmpty()) {
            throw ApiException.notFound("USER_NOT_FOUND", "No user " + userId);
        }
        Long seed = exploration ? ThreadLocalRandom.current().nextLong() : null;
        List<FeedRanker.Ranked> rankedA = rank(userId, a, seed);
        List<FeedRanker.Ranked> rankedB = b == null ? null : rank(userId, b, seed);

        Map<UUID, Post> visible = visiblePosts(userId, rankedA, rankedB, limit);
        List<FeedRanker.Ranked> shownA = shown(rankedA, visible, limit);
        List<FeedRanker.Ranked> shownB = rankedB == null ? null : shown(rankedB, visible, limit);

        List<UUID> idsA = shownA.stream().map(FeedRanker.Ranked::postId).toList();
        List<UUID> idsB = shownB == null
                ? null
                : shownB.stream().map(FeedRanker.Ranked::postId).toList();
        Double overlap = idsB == null ? null : RankingMetrics.overlapAt(OVERLAP_K, idsA, idsB);
        Double tau = null;
        int common = 0;
        if (idsB != null) {
            List<UUID> both = idsA.stream().filter(idsB::contains).toList();
            common = both.size();
            double[] x = new double[common];
            double[] y = new double[common];
            for (int i = 0; i < common; i++) {
                x[i] = idsA.indexOf(both.get(i));
                y[i] = idsB.indexOf(both.get(i));
            }
            tau = RankingMetrics.kendallTauB(x, y);
        }
        int candidates = inputs(userId, a.params()).featured().size();
        return new Preview(
                view(a, shownA, idsB, visible),
                b == null ? null : view(b, shownB, idsA, visible),
                overlap,
                tau,
                common,
                seed,
                candidates);
    }

    /** The user's candidates ranked under the config: the live feed's steps, nothing stored. */
    List<FeedRanker.Ranked> rank(UUID userId, RankingConfig config, @Nullable Long seed) {
        Inputs inputs = inputs(userId, config.params());
        return FeedRanker.rank(featuresFor(inputs, config.params()), config, seed, FeedRanker.Stages.NONE);
    }

    private Inputs inputs(UUID userId, RankingConfig.Params params) {
        Instant now = Instant.now(clock);
        cache.values()
                .removeIf(cached -> cached.now().plusSeconds(INPUT_TTL_SECONDS).isBefore(now));
        return cache.compute(Key.of(userId, params), (key, cached) -> {
            if (cached != null && !cached.now().plusSeconds(INPUT_TTL_SECONDS).isBefore(now)) {
                return cached;
            }
            Prior prior = priors.current();
            UserContext viewer = contexts.load(userId, params.seenWindowDays(), now);
            List<Candidate> candidates = generator.candidates(viewer, params, now);
            List<Featured> featured = generator.features(viewer, candidates, params, prior, now);
            return new Inputs(viewer, featured, params.halfLifeHours(), params.priorStrength(), prior, now);
        });
    }

    /** The cached features, or features recomputed from the cached facts when the two feature params differ. */
    private List<Featured> featuresFor(Inputs inputs, RankingConfig.Params params) {
        if (params.halfLifeHours() == inputs.halfLifeHours() && params.priorStrength() == inputs.priorStrength()) {
            return inputs.featured();
        }
        FeatureComputer.Scales scales = new FeatureComputer.Scales(
                params.halfLifeHours(),
                params.priorStrength(),
                inputs.prior().meanRate(),
                inputs.prior().p99Rate());
        FeatureComputer.Viewer who = inputs.viewer().viewer();
        return inputs.featured().stream()
                .map(f -> new Featured(
                        f.candidate(),
                        f.facts(),
                        FeatureComputer.compute(schema, who, f.facts(), scales, inputs.now())))
                .toList();
    }

    /** The posts the lists could show, as the user would see them (gone or blocked ones are missing). */
    private Map<UUID, Post> visiblePosts(
            UUID userId, List<FeedRanker.Ranked> a, @Nullable List<FeedRanker.Ranked> b, int limit) {
        Set<UUID> ids = new LinkedHashSet<>();
        a.stream().limit(limit + SPARE).forEach(r -> ids.add(r.postId()));
        if (b != null) {
            b.stream().limit(limit + SPARE).forEach(r -> ids.add(r.postId()));
        }
        Map<UUID, Post> visible = new HashMap<>();
        posts.byIds(userId, List.copyOf(ids)).forEach(post -> visible.put(post.id(), post));
        return visible;
    }

    private static List<FeedRanker.Ranked> shown(List<FeedRanker.Ranked> ranked, Map<UUID, Post> visible, int limit) {
        return ranked.stream()
                .filter(r -> visible.containsKey(r.postId()))
                .limit(limit)
                .toList();
    }

    private ListView view(
            RankingConfig config, List<FeedRanker.Ranked> shown, @Nullable List<UUID> other, Map<UUID, Post> visible) {
        List<Item> items = new ArrayList<>(shown.size());
        for (int i = 0; i < shown.size(); i++) {
            FeedRanker.Ranked r = shown.get(i);
            int otherIndex = other == null ? -1 : other.indexOf(r.postId());
            List<FeedItem.Contribution> contributions = new ArrayList<>(schema.size());
            for (int f = 0; f < schema.size(); f++) {
                contributions.add(new FeedItem.Contribution(
                        schema.names().get(f),
                        r.features().get(f),
                        config.weight(f),
                        r.scored().contributions()[f]));
            }
            items.add(new Item(
                    i + 1,
                    otherIndex < 0 ? null : otherIndex + 1,
                    card(visible.get(r.postId())),
                    r.score(),
                    r.scored().logit(),
                    config.bias(),
                    contributions,
                    r.sources().stream().sorted().map(CandidateSource::name).toList(),
                    r.exploration()));
        }
        return new ListView(RankingConfigs.INLINE.equals(config.id()) ? null : config.id(), config.version(), items);
    }

    static PostCard card(Post post) {
        return new PostCard(
                post.id(),
                post.author().id(),
                post.author().handle(),
                post.artists().stream().map(ArtistSummary::name).toList(),
                post.gig() == null ? null : post.gig().title(),
                post.playback() == null ? null : post.playback().thumbUrl(),
                post.caption(),
                post.publishedAt());
    }

    /** For tests: forget every cached input. */
    void clear() {
        cache.clear();
    }
}
