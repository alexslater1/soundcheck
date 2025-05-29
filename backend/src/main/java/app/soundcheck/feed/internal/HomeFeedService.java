package app.soundcheck.feed.internal;

import app.soundcheck.catalog.ArtistSummary;
import app.soundcheck.content.Post;
import app.soundcheck.content.PostFeed;
import app.soundcheck.feed.FeedItem;
import app.soundcheck.feed.HomeFeed;
import app.soundcheck.feed.internal.CandidateGenerator.Featured;
import app.soundcheck.feed.internal.CandidateRepository.Candidate;
import app.soundcheck.feed.internal.ServedItemLog.Served;
import app.soundcheck.feed.internal.ranking.CandidateSource;
import app.soundcheck.feed.internal.ranking.ExplorationInjector;
import app.soundcheck.feed.internal.ranking.ExplorationInjector.Slot;
import app.soundcheck.feed.internal.ranking.FeatureSchema;
import app.soundcheck.feed.internal.ranking.FeatureVector;
import app.soundcheck.feed.internal.ranking.MmrReranker;
import app.soundcheck.feed.internal.ranking.RankingConfig;
import app.soundcheck.feed.internal.ranking.ReasonMapper;
import app.soundcheck.feed.internal.ranking.Scorer;
import app.soundcheck.shared.Ids;
import app.soundcheck.shared.observability.Spans;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.signals.EngagementPriors;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ranked home feed.
 *
 * <p>The first request builds a <em>session</em>: user context → candidates → features → score → diversity
 * re-rank → exploration, at most {@code sessionSize} items, stored with their scores and features. That
 * request and every later page are then cut from the stored list. So the order is stable while the user
 * scrolls (scores computed a minute later would differ), nothing is ranked twice, and each item sent
 * out is logged with exactly the features it was ranked on.
 */
@Service
public class HomeFeedService {

    static final Duration SESSION_TTL = Duration.ofMinutes(30);
    static final int MAX_PAGE = 50;

    private final RankingConfigs configs;
    private final UserContextLoader contexts;
    private final CandidateGenerator generator;
    private final EngagementPriors priors;
    private final FeedSessionStore sessions;
    private final ServedItemLog served;
    private final PostFeed posts;
    private final FeatureSchema schema;
    private final MeterRegistry meters;
    private final Spans spans;
    private final Clock clock;

    HomeFeedService(
            RankingConfigs configs,
            UserContextLoader contexts,
            CandidateGenerator generator,
            EngagementPriors priors,
            FeedSessionStore sessions,
            ServedItemLog served,
            PostFeed posts,
            FeatureSchema schema,
            MeterRegistry meters,
            Spans spans,
            Clock clock) {
        this.configs = configs;
        this.contexts = contexts;
        this.generator = generator;
        this.priors = priors;
        this.sessions = sessions;
        this.served = served;
        this.posts = posts;
        this.schema = schema;
        this.meters = meters;
        this.spans = spans;
        this.clock = clock;
    }

    /**
     * A page of the home feed. Without a cursor a new session is built (this is also what
     * pull-to-refresh does); with one, the next page of that session is returned.
     *
     * @param debug include each item's score and contributions (the controller only passes true for admins)
     */
    @Transactional
    public HomeFeed home(UUID userId, @Nullable String cursor, @Nullable Integer limit, boolean debug) {
        Instant now = Instant.now(clock);
        if (cursor == null) {
            FeedSession session = build(userId, now);
            return page(session, 0, pageSize(limit, configs.byId(session.configId())), debug, now);
        }
        Position position = Position.parse(cursor);
        FeedSession session = sessions.find(position.sessionId())
                // Someone else's session is answered like one that does not exist.
                .filter(s -> s.userId().equals(userId) && !s.expiredAt(now))
                .orElseThrow(() -> ApiException.gone(
                        "FEED_SESSION_EXPIRED", "This feed session has expired; request the feed again"));
        return page(session, position.offset(), pageSize(limit, configs.byId(session.configId())), debug, now);
    }

    // ── building a session ───────────────────────────────────────────────────────────────────────

    private FeedSession build(UUID userId, Instant now) {
        RankingConfig config = configs.active();
        RankingConfig.Params params = config.params();

        UserContext viewer = stage("context", () -> contexts.load(userId, params.seenWindowDays(), now));
        List<Candidate> candidates = stage("candidates", () -> generator.candidates(viewer, params, now));
        countBySource(candidates);
        List<Featured> featured =
                stage("features", () -> generator.features(viewer, candidates, params, priors.current(), now));

        // Score every candidate, keeping what the session will store about it.
        Map<UUID, FeedSession.Item> scored = new HashMap<>();
        List<MmrReranker.Item> items = stage("score", () -> {
            List<MmrReranker.Item> list = new ArrayList<>(featured.size());
            for (Featured f : featured) {
                double score = Scorer.score(config, f.features()).score();
                Candidate c = f.candidate();
                scored.put(
                        c.postId(),
                        new FeedSession.Item(c.postId(), score, f.features().toArray(), false, c.sources()));
                list.add(new MmrReranker.Item(
                        c.postId(), score, c.authorId(), Set.copyOf(f.facts().artistIds()), c.gigId()));
            }
            return list;
        });
        List<MmrReranker.Item> ranked =
                stage("rerank", () -> MmrReranker.rerank(items, params.diversityLambda(), params.sessionSize()));

        UUID sessionId = Ids.newId();
        List<MmrReranker.Item> fresh = items.stream()
                .filter(item -> scored.get(item.postId()).sources().contains(CandidateSource.FRESH))
                .toList();
        List<Slot<MmrReranker.Item>> slots = stage(
                "explore",
                () -> ExplorationInjector.inject(
                        ranked,
                        fresh,
                        MmrReranker.Item::postId,
                        params.explorationRate(),
                        params.sessionSize(),
                        // The session id seeds the random draws: the same session can be rebuilt exactly.
                        sessionId.getMostSignificantBits() ^ sessionId.getLeastSignificantBits()));

        List<FeedSession.Item> ordered = slots.stream()
                .map(slot -> {
                    FeedSession.Item item = scored.get(slot.item().postId());
                    return new FeedSession.Item(
                            item.postId(), item.score(), item.features(), slot.exploration(), item.sources());
                })
                .toList();
        FeedSession session = new FeedSession(sessionId, userId, config.id(), now, now.plus(SESSION_TTL), ordered);
        stage("store", () -> {
            sessions.save(session);
            return null;
        });
        DistributionSummary.builder("soundcheck.feed.session.size")
                .register(meters)
                .record(ordered.size());
        return session;
    }

    // ── serving a page ───────────────────────────────────────────────────────────────────────────

    private HomeFeed page(FeedSession session, int offset, int limit, boolean debug, Instant now) {
        int from = Math.min(offset, session.items().size());
        int to = Math.min(from + limit, session.items().size());
        List<FeedSession.Item> slice = session.items().subList(from, to);
        RankingConfig config = configs.byId(session.configId());

        Map<UUID, Post> visible = new HashMap<>();
        posts.byIds(
                        session.userId(),
                        slice.stream().map(FeedSession.Item::postId).toList())
                .forEach(post -> visible.put(post.id(), post));

        List<FeedItem> items = new ArrayList<>(slice.size());
        List<Served> log = new ArrayList<>(slice.size());
        for (int i = 0; i < slice.size(); i++) {
            FeedSession.Item item = slice.get(i);
            Post post = visible.get(item.postId());
            if (post == null) {
                continue; // deleted, or its author blocked, since the session was built
            }
            int position = from + i;
            FeatureVector features = FeatureVector.of(item.features());
            Scorer.Scored scored = Scorer.score(config, features);
            items.add(new FeedItem(
                    post,
                    position,
                    ReasonMapper.reasons(
                            schema, scored.contributions(), item.sources(), item.exploration(), context(post, item)),
                    debug ? debug(config, item, scored) : null));
            log.add(new Served(position, item.postId(), item.score(), features.toFloats(), item.exploration()));
        }
        served.record(session.id(), session.userId(), session.configId(), now, log);
        String next = to < session.items().size() ? session.id() + ":" + to : null;
        return new HomeFeed(session.id(), items, next);
    }

    /** What the reasons' sentences need: names from the post, and the friend count behind social_proof. */
    private ReasonMapper.Context context(Post post, FeedSession.Item item) {
        String followedArtist = post.artists().stream()
                .filter(ArtistSummary::isFollowing)
                .map(ArtistSummary::name)
                .findFirst()
                .orElse(null);
        // social_proof = 1 - exp(-n / 2), so n = -2·ln(1 - social_proof).
        double socialProof = item.features()[schema.indexOf("social_proof")];
        int friends = socialProof >= 1 ? 99 : (int) Math.round(-2 * Math.log(1 - socialProof));
        return new ReasonMapper.Context(
                post.author().handle(),
                followedArtist,
                post.gig() == null ? null : post.gig().myAttendance(),
                friends);
    }

    private FeedItem.Debug debug(RankingConfig config, FeedSession.Item item, Scorer.Scored scored) {
        List<FeedItem.Contribution> contributions = new ArrayList<>(schema.size());
        for (int i = 0; i < schema.size(); i++) {
            contributions.add(new FeedItem.Contribution(
                    schema.names().get(i), item.features()[i], config.weight(i), scored.contributions()[i]));
        }
        return new FeedItem.Debug(item.score(), contributions);
    }

    private static int pageSize(@Nullable Integer limit, RankingConfig config) {
        return limit == null ? config.params().pageSize() : Math.max(1, Math.min(limit, MAX_PAGE));
    }

    // ── cursor, timing ───────────────────────────────────────────────────────────────────────────

    /** {@code {sessionId}:{offset}}. */
    record Position(UUID sessionId, int offset) {

        static Position parse(String cursor) {
            int colon = cursor.lastIndexOf(':');
            try {
                int offset = Integer.parseInt(cursor.substring(colon + 1));
                if (colon > 0 && offset >= 0) {
                    return new Position(UUID.fromString(cursor.substring(0, colon)), offset);
                }
            } catch (IllegalArgumentException e) {
                // falls through to the 400
            }
            throw ApiException.badRequest("CURSOR_INVALID", "Not a feed cursor");
        }
    }

    /** Runs one stage of the pipeline inside a span and a timer ({@code soundcheck.feed.stage}). */
    private <T> T stage(String name, Supplier<T> work) {
        Timer.Sample sample = Timer.start(meters);
        try (Spans.Scope scope = spans.child("feed." + name)) {
            try {
                return work.get();
            } catch (RuntimeException e) {
                scope.error(e);
                throw e;
            }
        } finally {
            sample.stop(
                    Timer.builder("soundcheck.feed.stage").tag("stage", name).register(meters));
        }
    }

    private void countBySource(List<Candidate> candidates) {
        Map<CandidateSource, Integer> bySource = new HashMap<>();
        for (Candidate candidate : candidates) {
            candidate.sources().forEach(source -> bySource.merge(source, 1, Integer::sum));
        }
        for (CandidateSource source : CandidateSource.values()) {
            DistributionSummary.builder("soundcheck.feed.candidates")
                    .tag("source", source.name())
                    .register(meters)
                    .record(bySource.getOrDefault(source, 0));
        }
    }
}
