package app.soundcheck.signals.internal;

import app.soundcheck.content.PostFacts;
import app.soundcheck.signals.internal.EventLog.Position;
import app.soundcheck.signals.internal.EventLog.UserPostKind;
import app.soundcheck.signals.internal.PostStatsRepository.Claim;
import app.soundcheck.signals.internal.PostStatsRepository.HourTotals;
import app.soundcheck.signals.internal.PostStatsRepository.Totals;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folds new events into the per-post counters: the all-time
 * {@code post_stats} that ranking reads, and {@code post_stats_hourly} for trending.
 *
 * <p><b>Exactly once.</b> One batch is one transaction: read the watermark (locked), read at most
 * {@code batchSize} events after it, add them to the counters, move the watermark. If anything fails,
 * all of it rolls back and the next run reads the same events again; if it commits, they are never read
 * again.
 *
 * <p><b>One user, one count.</b> The counters count people, not events. Events come from clients, and a
 * client can send the same event a hundred times (a bug, a retry, or someone trying to make a post
 * trend). So each (post, user, kind) is counted the first time it is seen and never again
 * ({@code signals.post_user_event}); a like can be taken back and given again, which moves the like
 * counter down and up but never counts as fresh engagement twice.
 */
@Service
public class PostStatsRollup {

    static final String NAME = "post_stats";
    public static final int BATCH = 50_000;

    private final EventLog log;
    private final PostStatsRepository stats;
    private final PostFacts posts;
    private final Clock clock;

    PostStatsRollup(EventLog log, PostStatsRepository stats, PostFacts posts, Clock clock) {
        this.log = log;
        this.stats = stats;
        this.posts = posts;
        this.clock = clock;
    }

    /** {@link #rollUpBatch(Duration, int)} with the standard batch size. */
    @Transactional
    public int rollUpBatch(Duration settle) {
        return rollUpBatch(settle, BATCH);
    }

    /**
     * Folds in one batch of the events that are at least {@code settle} old.
     *
     * @return how many events the batch held (0: nothing new; {@code batchSize}: there may be more)
     */
    @Transactional
    public int rollUpBatch(Duration settle, int batchSize) {
        Position from = log.watermarkForUpdate(NAME);
        Optional<Position> end = log.endOfBatch(from, settle, batchSize);
        if (end.isEmpty()) {
            return 0;
        }
        List<UserPostKind> seen = log.byUserPostAndKind(from, end.get());

        // Events are not checked at intake: counters are only kept for posts that are published.
        Set<UUID> published = new HashSet<>();
        List<UUID> postIds = seen.stream().map(UserPostKind::postId).distinct().toList();
        for (List<UUID> chunk : AffinityRepository.chunks(postIds, 1_000)) {
            published.addAll(posts.published(chunk));
        }

        // Likes and unlikes are a state, not a count: what matters is which came last.
        Map<PostUser, UserPostKind> lastLike = new HashMap<>();
        Map<PostUser, UserPostKind> lastUnlike = new HashMap<>();
        List<Claim> claims = new ArrayList<>();
        for (UserPostKind event : seen) {
            if (!published.contains(event.postId())) {
                continue;
            }
            PostUser key = new PostUser(event.postId(), event.userId());
            switch (event.kind()) {
                case "LIKE" -> lastLike.put(key, event);
                case "UNLIKE" -> lastUnlike.put(key, event);
                default -> claims.add(new Claim(event.postId(), event.userId(), event.kind(), event.firstAt()));
            }
        }
        Map<UUID, long[]> perPost = new HashMap<>();
        Map<HourKey, long[]> perHour = new HashMap<>();
        lastLike.forEach((key, like) -> {
            UserPostKind unlike = lastUnlike.get(key);
            if (unlike == null || like.lastId() > unlike.lastId()) {
                // Ends the batch liked: a first like is claimed below; one given again is turned back on.
                claims.add(new Claim(key.postId(), key.userId(), "LIKE", like.firstAt()));
                if (stats.setLikeActive(key.postId(), key.userId(), true)) {
                    perPost.computeIfAbsent(key.postId(), id -> new long[SLOTS])[LIKES]++;
                }
            }
        });
        lastUnlike.forEach((key, unlike) -> {
            UserPostKind like = lastLike.get(key);
            if ((like == null || unlike.lastId() > like.lastId())
                    && stats.setLikeActive(key.postId(), key.userId(), false)) {
                perPost.computeIfAbsent(key.postId(), id -> new long[SLOTS])[LIKES]--;
            }
        });

        // Whatever was not counted before is counted now, in the hour it first happened.
        for (Claim first : stats.claimFirst(claims)) {
            int slot = slot(first.kind());
            perPost.computeIfAbsent(first.postId(), id -> new long[SLOTS])[slot]++;
            long[] hour = perHour.computeIfAbsent(
                    new HourKey(first.postId(), first.firstAt().truncatedTo(ChronoUnit.HOURS)), k -> new long[SLOTS]);
            hour[slot]++;
        }

        List<Totals> totals = new ArrayList<>();
        perPost.forEach((postId, c) -> totals.add(new Totals(
                postId,
                c[IMPRESSIONS],
                c[VIEWS],
                c[COMPLETES],
                c[LIKES],
                c[COMMENTS],
                c[SHARES],
                c[HIDES],
                // Each progress mark (25, 50, 75 %) and the completion is another quarter watched.
                0.25 * (c[QUARTERS] + c[COMPLETES]))));
        List<HourTotals> hourly = new ArrayList<>();
        perHour.forEach((key, c) -> hourly.add(new HourTotals(key.postId(), key.hour(), (int) c[IMPRESSIONS], (int)
                (c[LIKES] + c[COMMENTS] + c[SHARES] + c[COMPLETES]))));

        stats.add(totals, Instant.now(clock));
        stats.addHourly(hourly);
        int events = log.count(from, end.get());
        log.advance(NAME, end.get());
        return events;
    }

    private record PostUser(UUID postId, UUID userId) {}

    private record HourKey(UUID postId, Instant hour) {}

    private static final int IMPRESSIONS = 0;
    private static final int VIEWS = 1;
    private static final int QUARTERS = 2;
    private static final int COMPLETES = 3;
    private static final int LIKES = 4;
    private static final int COMMENTS = 5;
    private static final int SHARES = 6;
    private static final int HIDES = 7;
    private static final int SLOTS = 8;

    /** The counter a kind feeds. */
    private static int slot(String kind) {
        return switch (kind) {
            case "IMPRESSION" -> IMPRESSIONS;
            case "VIEW_START" -> VIEWS;
            case "WATCH_25", "WATCH_50", "WATCH_75" -> QUARTERS;
            case "COMPLETE" -> COMPLETES;
            case "LIKE" -> LIKES;
            case "COMMENT" -> COMMENTS;
            case "SHARE" -> SHARES;
            case "HIDE" -> HIDES;
            default -> throw new IllegalArgumentException("no counter for " + kind);
        };
    }
}
