package app.soundcheck.signals.internal;

import app.soundcheck.content.PostFacts;
import app.soundcheck.signals.internal.EventLog.Position;
import app.soundcheck.signals.internal.EventLog.PostHourCount;
import app.soundcheck.signals.internal.PostStatsRepository.HourTotals;
import app.soundcheck.signals.internal.PostStatsRepository.Totals;
import java.time.Clock;
import java.time.Instant;
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
 * <p>One batch is one transaction: read the watermark (locked), read at most {@link #BATCH} events
 * after it, add them to the counters, move the watermark. If anything fails, all of it rolls back and
 * the next run reads the same events again; if it commits, they are never read again. So each event is
 * counted exactly once, without marking events as processed.
 */
@Service
public class PostStatsRollup {

    static final String NAME = "post_stats";
    static final int BATCH = 50_000;

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

    /**
     * Folds in one batch of the events that arrived up to {@code upTo}.
     *
     * @return how many events the batch held (0: nothing new; {@link #BATCH}: there may be more)
     */
    @Transactional
    public int rollUpBatch(Instant upTo) {
        Position from = log.watermarkForUpdate(NAME);
        Optional<Position> end = log.endOfBatch(from, upTo, BATCH);
        if (end.isEmpty()) {
            return 0;
        }
        List<PostHourCount> counts = log.countsByPostAndHour(from, end.get());

        // Events are not checked at intake: counters are only kept for posts that (still) exist.
        Set<UUID> existing = new HashSet<>();
        List<UUID> postIds =
                counts.stream().map(PostHourCount::postId).distinct().toList();
        for (List<UUID> chunk : AffinityRepository.chunks(postIds, 1_000)) {
            existing.addAll(posts.existing(chunk));
        }

        Map<UUID, long[]> perPost = new HashMap<>();
        Map<HourKey, long[]> perHour = new HashMap<>();
        int events = 0;
        for (PostHourCount count : counts) {
            events += (int) count.count();
            if (!existing.contains(count.postId())) {
                continue;
            }
            int slot = slot(count.eventType());
            if (slot >= 0) {
                perPost.computeIfAbsent(count.postId(), id -> new long[SLOTS])[slot] += count.count();
                perHour.computeIfAbsent(new HourKey(count.postId(), count.hour()), k -> new long[SLOTS])[slot] +=
                        count.count();
            }
        }
        List<Totals> totals = new ArrayList<>();
        perPost.forEach((postId, c) -> totals.add(new Totals(
                postId,
                c[IMPRESSION],
                c[VIEW_START],
                c[COMPLETE],
                c[LIKE] - c[UNLIKE],
                c[COMMENT],
                c[SHARE],
                c[HIDE],
                // Each progress mark (25, 50, 75 %) and the completion is another quarter watched.
                0.25 * (c[WATCH_PROGRESS] + c[COMPLETE]))));
        List<HourTotals> hourly = new ArrayList<>();
        perHour.forEach((key, c) -> hourly.add(new HourTotals(key.postId(), key.hour(), (int) c[IMPRESSION], (int)
                (Math.max(0, c[LIKE] - c[UNLIKE]) + c[COMMENT] + c[SHARE] + c[COMPLETE]))));

        stats.add(totals, Instant.now(clock));
        stats.addHourly(hourly);
        log.advance(NAME, end.get());
        return events;
    }

    private record HourKey(UUID postId, Instant hour) {}

    private static final int IMPRESSION = 0;
    private static final int VIEW_START = 1;
    private static final int WATCH_PROGRESS = 2;
    private static final int COMPLETE = 3;
    private static final int LIKE = 4;
    private static final int UNLIKE = 5;
    private static final int COMMENT = 6;
    private static final int SHARE = 7;
    private static final int HIDE = 8;
    private static final int SLOTS = 9;

    /** The counter an event type feeds; -1 for types the post counters do not use (taps, reports). */
    private static int slot(String eventType) {
        return switch (eventType) {
            case "IMPRESSION" -> IMPRESSION;
            case "VIEW_START" -> VIEW_START;
            case "WATCH_PROGRESS" -> WATCH_PROGRESS;
            case "COMPLETE" -> COMPLETE;
            case "LIKE" -> LIKE;
            case "UNLIKE" -> UNLIKE;
            case "COMMENT" -> COMMENT;
            case "SHARE" -> SHARE;
            case "HIDE" -> HIDE;
            default -> -1;
        };
    }
}
