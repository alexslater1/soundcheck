package app.soundcheck.signals.internal;

import static app.soundcheck.shared.jooq.signals.Tables.GLOBAL_PRIOR;
import static app.soundcheck.shared.jooq.signals.Tables.POST_STATS;
import static app.soundcheck.shared.jooq.signals.Tables.POST_STATS_HOURLY;
import static app.soundcheck.shared.jooq.signals.Tables.POST_USER_EVENT;
import static org.jooq.impl.DSL.excluded;

import app.soundcheck.signals.EngagementPriors.Prior;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** The per-post counters ({@code signals.post_stats}, {@code post_stats_hourly}) and the global prior. */
@Repository
class PostStatsRepository {

    /**
     * What a batch adds to one post's all-time counters. Each counts users, not events; {@code likes}
     * is new likes minus likes taken back.
     */
    record Totals(
            UUID postId,
            long impressions,
            long views,
            long completes,
            long likes,
            long comments,
            long shares,
            long hides,
            double watchFraction) {}

    /** A (post, user, kind) that may be counted, if it has not been before. */
    record Claim(UUID postId, UUID userId, String kind, Instant firstAt) {}

    /** What a batch adds to one post's hour. */
    record HourTotals(UUID postId, Instant hour, int impressions, int engagements) {}

    private final DSLContext dsl;

    PostStatsRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Adds the batch to the all-time counters. The rollup is the only writer (it holds its watermark
     * row), so "which posts already have a row" cannot change underneath it: existing rows are updated,
     * the others inserted. Likes never go below zero.
     */
    void add(Collection<Totals> totals, Instant now) {
        if (totals.isEmpty()) {
            return;
        }
        Set<UUID> known = new HashSet<>(dsl.select(POST_STATS.POST_ID)
                .from(POST_STATS)
                .where(POST_STATS.POST_ID.in(totals.stream().map(Totals::postId).toList()))
                .fetch(POST_STATS.POST_ID));
        List<Query> updates = new ArrayList<>();
        var insert = dsl.insertInto(
                POST_STATS,
                POST_STATS.POST_ID,
                POST_STATS.IMPRESSIONS,
                POST_STATS.VIEWS,
                POST_STATS.COMPLETES,
                POST_STATS.LIKES,
                POST_STATS.COMMENTS,
                POST_STATS.SHARES,
                POST_STATS.HIDES,
                POST_STATS.WATCH_FRACTION_SUM,
                POST_STATS.UPDATED_AT);
        boolean anyNew = false;
        for (Totals t : totals) {
            if (known.contains(t.postId())) {
                updates.add(dsl.update(POST_STATS)
                        .set(POST_STATS.IMPRESSIONS, POST_STATS.IMPRESSIONS.plus(t.impressions()))
                        .set(POST_STATS.VIEWS, POST_STATS.VIEWS.plus(t.views()))
                        .set(POST_STATS.COMPLETES, POST_STATS.COMPLETES.plus(t.completes()))
                        .set(POST_STATS.LIKES, DSL.greatest(DSL.inline(0L), POST_STATS.LIKES.plus(t.likes())))
                        .set(POST_STATS.COMMENTS, POST_STATS.COMMENTS.plus(t.comments()))
                        .set(POST_STATS.SHARES, POST_STATS.SHARES.plus(t.shares()))
                        .set(POST_STATS.HIDES, POST_STATS.HIDES.plus(t.hides()))
                        .set(POST_STATS.WATCH_FRACTION_SUM, POST_STATS.WATCH_FRACTION_SUM.plus(t.watchFraction()))
                        .set(POST_STATS.UPDATED_AT, at(now))
                        .where(POST_STATS.POST_ID.eq(t.postId())));
            } else {
                insert = insert.values(
                        t.postId(),
                        t.impressions(),
                        t.views(),
                        t.completes(),
                        Math.max(0, t.likes()),
                        t.comments(),
                        t.shares(),
                        t.hides(),
                        t.watchFraction(),
                        at(now));
                anyNew = true;
            }
        }
        if (!updates.isEmpty()) {
            dsl.batch(updates).execute();
        }
        if (anyNew) {
            insert.execute();
        }
    }

    /** Adds the batch to the hourly counters (the trending window). */
    void addHourly(Collection<HourTotals> totals) {
        if (totals.isEmpty()) {
            return;
        }
        var insert = dsl.insertInto(
                POST_STATS_HOURLY,
                POST_STATS_HOURLY.POST_ID,
                POST_STATS_HOURLY.HOUR,
                POST_STATS_HOURLY.IMPRESSIONS,
                POST_STATS_HOURLY.ENGAGEMENTS);
        for (HourTotals t : totals) {
            insert = insert.values(t.postId(), at(t.hour()), t.impressions(), t.engagements());
        }
        insert.onConflict(POST_STATS_HOURLY.HOUR, POST_STATS_HOURLY.POST_ID)
                .doUpdate()
                .set(
                        POST_STATS_HOURLY.IMPRESSIONS,
                        POST_STATS_HOURLY.IMPRESSIONS.plus(excluded(POST_STATS_HOURLY.IMPRESSIONS)))
                .set(
                        POST_STATS_HOURLY.ENGAGEMENTS,
                        POST_STATS_HOURLY.ENGAGEMENTS.plus(excluded(POST_STATS_HOURLY.ENGAGEMENTS)))
                .execute();
    }

    // ── counting each user once ──────────────────────────────────────────────────────────────────

    /**
     * Records that these users did these things to these posts, and returns the ones that were not
     * recorded before: only those are counted. A second SHARE of the same post by the same user, in
     * this batch or a later one, inserts nothing and counts nothing.
     */
    List<Claim> claimFirst(Collection<Claim> claims) {
        List<Claim> first = new ArrayList<>();
        List<Claim> all = List.copyOf(claims);
        for (int from = 0; from < all.size(); from += 1_000) {
            var insert = dsl.insertInto(
                    POST_USER_EVENT,
                    POST_USER_EVENT.POST_ID,
                    POST_USER_EVENT.USER_ID,
                    POST_USER_EVENT.KIND,
                    POST_USER_EVENT.FIRST_AT);
            for (Claim claim : all.subList(from, Math.min(all.size(), from + 1_000))) {
                insert = insert.values(claim.postId(), claim.userId(), claim.kind(), at(claim.firstAt()));
            }
            insert.onConflictDoNothing()
                    .returning(
                            POST_USER_EVENT.POST_ID,
                            POST_USER_EVENT.USER_ID,
                            POST_USER_EVENT.KIND,
                            POST_USER_EVENT.FIRST_AT)
                    .fetch()
                    .forEach(r -> first.add(new Claim(
                            r.getPostId(),
                            r.getUserId(),
                            r.getKind(),
                            r.getFirstAt().toInstant())));
        }
        return first;
    }

    /**
     * Turns a user's like of a post off (an UNLIKE) or back on (a like after an unlike).
     *
     * @return true when the row existed in the other state and was changed
     */
    boolean setLikeActive(UUID postId, UUID userId, boolean active) {
        return dsl.update(POST_USER_EVENT)
                        .set(POST_USER_EVENT.ACTIVE, active)
                        .where(POST_USER_EVENT.POST_ID.eq(postId))
                        .and(POST_USER_EVENT.USER_ID.eq(userId))
                        .and(POST_USER_EVENT.KIND.eq("LIKE"))
                        .and(POST_USER_EVENT.ACTIVE.eq(!active))
                        .execute()
                == 1;
    }

    /** Hourly counters are only read for the last week; older ones are removed. */
    int deleteHourlyBefore(Instant before) {
        return dsl.deleteFrom(POST_STATS_HOURLY)
                .where(POST_STATS_HOURLY.HOUR.lt(at(before)))
                .execute();
    }

    // ── the global prior ─────────────────────────────────────────────────────────────────────────

    /** Weighted engagement of a post, as in ranking feature 8: likes + 2·comments + shares + completes. */
    private static Field<Double> engagement() {
        return POST_STATS
                .LIKES
                .plus(POST_STATS.COMMENTS.times(2))
                .plus(POST_STATS.SHARES)
                .plus(POST_STATS.COMPLETES)
                .cast(Double.class);
    }

    /**
     * Recomputes the prior from every post that has been shown at least once: the overall rate
     * {@code m}, and the 99th percentile of the per-post rate smoothed towards {@code m} with
     * {@code strength} pseudo-impressions.
     */
    Prior refreshPrior(double strength, Instant now) {
        var sums = dsl.select(DSL.sum(engagement()), DSL.sum(POST_STATS.IMPRESSIONS), DSL.count())
                .from(POST_STATS)
                .where(POST_STATS.IMPRESSIONS.gt(0L))
                .fetchSingle();
        int posts = sums.value3();
        double mean =
                posts == 0 ? 0 : sums.value1().doubleValue() / sums.value2().doubleValue();
        Field<Double> smoothed = engagement()
                .plus(DSL.inline(strength * mean))
                .div(POST_STATS.IMPRESSIONS.cast(Double.class).plus(DSL.inline(strength)));
        Double p99 = posts == 0
                ? null
                : dsl.select(DSL.percentileCont(0.99).withinGroupOrderBy(smoothed))
                        .from(POST_STATS)
                        .where(POST_STATS.IMPRESSIONS.gt(0L))
                        .fetchSingle()
                        .value1()
                        .doubleValue();
        Prior prior = new Prior(mean, p99 == null ? 0 : p99);
        dsl.insertInto(GLOBAL_PRIOR)
                .set(GLOBAL_PRIOR.ID, true)
                .set(GLOBAL_PRIOR.MEAN_RATE, prior.meanRate())
                .set(GLOBAL_PRIOR.P99_RATE, prior.p99Rate())
                .set(GLOBAL_PRIOR.POSTS, posts)
                .set(GLOBAL_PRIOR.UPDATED_AT, at(now))
                .onConflict(GLOBAL_PRIOR.ID)
                .doUpdate()
                .set(GLOBAL_PRIOR.MEAN_RATE, prior.meanRate())
                .set(GLOBAL_PRIOR.P99_RATE, prior.p99Rate())
                .set(GLOBAL_PRIOR.POSTS, posts)
                .set(GLOBAL_PRIOR.UPDATED_AT, at(now))
                .execute();
        return prior;
    }

    Optional<Prior> prior() {
        return dsl.selectFrom(GLOBAL_PRIOR).fetchOptional(r -> new Prior(r.getMeanRate(), r.getP99Rate()));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
