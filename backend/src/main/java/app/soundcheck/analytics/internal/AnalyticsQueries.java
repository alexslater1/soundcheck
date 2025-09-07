package app.soundcheck.analytics.internal;

import static app.soundcheck.shared.jooq.analytics.Tables.MEDIA_FACT;
import static app.soundcheck.shared.jooq.analytics.Tables.POST_FACT;
import static app.soundcheck.shared.jooq.analytics.Tables.SERVE_FACT;
import static app.soundcheck.shared.jooq.analytics.Tables.USER_FACT;
import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE;
import static app.soundcheck.shared.jooq.content.Tables.POST_CLIP;
import static app.soundcheck.shared.jooq.signals.Tables.INTERACTION_EVENT;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.CommonTableExpression;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The console's analytics SQL. Every query aggregates the row-level fact views of V137
 * (or the event log) over a period of raw timestamps, so the planner prunes the daily partitions of
 * {@code feed.served_item} and {@code signals.interaction_event} and uses the range indexes; nothing here
 * filters on a computed day. Days are UK days ({@code Europe/London}), bucketed in SQL.
 *
 * <p>The overview's queries read the current period and the one before it in one pass: each row says
 * which period it belongs to ({@code current}), and is grouped by that and the day. They group by column
 * position ({@code group by 1, 2}) because the period test carries a bind value, which PostgreSQL would not
 * match between the select list and a repeated expression.
 *
 * <p>Each method's comment names the index or partition key it expects.
 */
@Component
class AnalyticsQueries {

    /** A UK calendar day: the timestamp's local date in London (GMT or BST). */
    static Field<LocalDate> londonDay(Field<OffsetDateTime> at) {
        return DSL.field("({0} at time zone 'Europe/London')::date", SQLDataType.LOCALDATE, at);
    }

    private static final Field<Integer> FIRST = DSL.inline(1);
    private static final Field<Integer> SECOND = DSL.inline(2);

    private final DSLContext dsl;

    AnalyticsQueries(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** One UK day of the overview's counts, in the current period or the previous one. */
    interface PeriodDay {
        boolean current();

        LocalDate day();
    }

    record ActivityDay(boolean current, LocalDate day, long real, long synthetic, long events, long syntheticEvents)
            implements PeriodDay {}

    record CountDay(boolean current, LocalDate day, long count, long synthetic) implements PeriodDay {}

    record UploadDay(boolean current, LocalDate day, long uploads, long ready, long failed, @Nullable Double p50Ms)
            implements PeriodDay {}

    record ServeDay(boolean current, LocalDate day, long served, long positive) implements PeriodDay {}

    record Percentiles(@Nullable Double p50, @Nullable Double p95) {}

    /**
     * Who did something, per period and UK day: distinct real and synthetic users (an account at a
     * synthetic domain; an event of an account that no longer exists counts as real), and all events.
     * Expected: interaction_event's partition key (occurred_at) prunes the two periods' partitions; the
     * accounts by user_fact's (app_user's) primary key, or one hash join.
     */
    List<ActivityDay> activity(Instant previousFrom, Instant from, Instant to, List<String> syntheticDomains) {
        Field<Boolean> synthetic = synthetic(syntheticDomains);
        return dsl.select(
                        DSL.field(INTERACTION_EVENT.OCCURRED_AT.ge(at(from))),
                        londonDay(INTERACTION_EVENT.OCCURRED_AT),
                        DSL.countDistinct(INTERACTION_EVENT.USER_ID).filterWhere(DSL.not(DSL.condition(synthetic))),
                        DSL.countDistinct(INTERACTION_EVENT.USER_ID).filterWhere(DSL.condition(synthetic)),
                        DSL.count(),
                        DSL.count().filterWhere(DSL.condition(synthetic)))
                .from(INTERACTION_EVENT)
                .leftJoin(USER_FACT)
                .on(USER_FACT.USER_ID.eq(INTERACTION_EVENT.USER_ID))
                .where(INTERACTION_EVENT.OCCURRED_AT.ge(at(previousFrom)))
                .and(INTERACTION_EVENT.OCCURRED_AT.lt(at(to)))
                .groupBy(FIRST, SECOND)
                .fetch(r -> new ActivityDay(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6()));
    }

    /**
     * Weekly active users: distinct users with an event in the 7 days up to {@code end}, for each given end.
     * Expected: interaction_event's partition key prunes to the 7-day windows (the OR of two ranges).
     */
    Record2<Integer, Integer> weeklyActive(Instant end, Instant previousEnd) {
        Condition current = window(end.minus(Duration.ofDays(7)), end);
        Condition previous = window(previousEnd.minus(Duration.ofDays(7)), previousEnd);
        return dsl.select(
                        DSL.countDistinct(INTERACTION_EVENT.USER_ID).filterWhere(current),
                        DSL.countDistinct(INTERACTION_EVENT.USER_ID).filterWhere(previous))
                .from(INTERACTION_EVENT)
                .where(current.or(previous))
                .fetchSingle();
    }

    /**
     * Weekly active users as of each UK day from {@code firstDay} to {@code lastDay}: distinct users with an
     * event on that day or the six before it (the last day counts up to {@code to}).
     * Expected: interaction_event's partition key prunes to [from - 8 days, to) (a week before the first day,
     * with a day to spare for the clock change); the (user, day) pairs
     * are then joined to the days, so the cost grows with active users times days.
     */
    List<Record2<LocalDate, Integer>> weeklyActiveByDay(
            LocalDate firstDay, LocalDate lastDay, Instant from, Instant to) {
        Field<LocalDate> eventDay = londonDay(INTERACTION_EVENT.OCCURRED_AT).as("day");
        var userDays = dsl.selectDistinct(INTERACTION_EVENT.USER_ID, eventDay)
                .from(INTERACTION_EVENT)
                .where(INTERACTION_EVENT.OCCURRED_AT.ge(at(from.minus(Duration.ofDays(8)))))
                .and(INTERACTION_EVENT.OCCURRED_AT.lt(at(to)))
                .asTable("ud");
        Field<LocalDate> udDay = userDays.field("day", LocalDate.class);
        Field<UUID> udUser = userDays.field(INTERACTION_EVENT.USER_ID);
        var days = DSL.table(
                        "generate_series({0}::date, {1}::date, interval '1 day')", DSL.val(firstDay), DSL.val(lastDay))
                .as("d", "day");
        Field<LocalDate> day = DSL.field(DSL.name("d", "day"), SQLDataType.LOCALDATE);
        return dsl.select(DSL.field("{0}::date", SQLDataType.LOCALDATE, day), DSL.countDistinct(udUser))
                .from(days)
                .join(userDays)
                .on(DSL.condition("{0} between {1}::date - 6 and {1}::date", udDay, day))
                .groupBy(FIRST)
                .orderBy(FIRST)
                .fetch();
    }

    /** New accounts per period and UK day, and how many of them are synthetic. Expected: app_user_created (V131). */
    List<CountDay> newUsers(Instant previousFrom, Instant from, Instant to, List<String> syntheticDomains) {
        Field<Boolean> synthetic = synthetic(syntheticDomains);
        return dsl.select(
                        DSL.field(USER_FACT.CREATED_AT.ge(at(from))),
                        londonDay(USER_FACT.CREATED_AT),
                        DSL.count(),
                        DSL.count().filterWhere(DSL.condition(synthetic)))
                .from(USER_FACT)
                .where(USER_FACT.CREATED_AT.ge(at(previousFrom)))
                .and(USER_FACT.CREATED_AT.lt(at(to)))
                .groupBy(FIRST, SECOND)
                .fetch(r -> new CountDay(r.value1(), r.value2(), r.value3(), r.value4()));
    }

    /**
     * Posts published per period and UK day (whatever their status now), and how many by synthetic authors.
     * Expected: post_published_at (V138); per post, post_clip_post for the view's clip columns; the author
     * by app_user's primary key.
     */
    List<CountDay> posts(Instant previousFrom, Instant from, Instant to, List<String> syntheticDomains) {
        Field<Boolean> synthetic = synthetic(syntheticDomains);
        return dsl.select(
                        DSL.field(POST_FACT.PUBLISHED_AT.ge(at(from))),
                        londonDay(POST_FACT.PUBLISHED_AT),
                        DSL.count(),
                        DSL.count().filterWhere(DSL.condition(synthetic)))
                .from(POST_FACT)
                .leftJoin(USER_FACT)
                .on(USER_FACT.USER_ID.eq(POST_FACT.AUTHOR_ID))
                .where(POST_FACT.PUBLISHED_AT.ge(at(previousFrom)))
                .and(POST_FACT.PUBLISHED_AT.lt(at(to)))
                .groupBy(FIRST, SECOND)
                .fetch(r -> new CountDay(r.value1(), r.value2(), r.value3(), r.value4()));
    }

    /**
     * Completed uploads per period and UK day: how many, how many became READY or FAILED, and the median
     * time to ready. Expected: media_asset_uploaded_at (V138).
     */
    List<UploadDay> uploads(Instant previousFrom, Instant from, Instant to) {
        return dsl.select(
                        DSL.field(MEDIA_FACT.UPLOADED_AT.ge(at(from))),
                        londonDay(MEDIA_FACT.UPLOADED_AT),
                        DSL.count(),
                        DSL.count().filterWhere(MEDIA_FACT.OUTCOME.eq("READY")),
                        DSL.count().filterWhere(MEDIA_FACT.OUTCOME.eq("FAILED")),
                        DSL.percentileCont(0.5).withinGroupOrderBy(MEDIA_FACT.TIME_TO_READY_MS))
                .from(MEDIA_FACT)
                .where(MEDIA_FACT.UPLOADED_AT.ge(at(previousFrom)))
                .and(MEDIA_FACT.UPLOADED_AT.lt(at(to)))
                .groupBy(FIRST, SECOND)
                .fetch(r -> new UploadDay(
                        r.value1(),
                        r.value2(),
                        r.value3(),
                        r.value4(),
                        r.value5(),
                        r.value6() == null ? null : r.value6().doubleValue()));
    }

    /**
     * The 50th and 95th percentile of time to ready (ms) of the uploads in [{@code from}, {@code to}),
     * over those that became READY. Expected: media_asset_uploaded_at (V138).
     */
    Percentiles timeToReady(Instant from, Instant to) {
        Double[] p = dsl.select(DSL.field(
                        "percentile_cont(array[0.5, 0.95]) within group (order by {0})",
                        SQLDataType.DOUBLE.array(), MEDIA_FACT.TIME_TO_READY_MS))
                .from(MEDIA_FACT)
                .where(MEDIA_FACT.UPLOADED_AT.ge(at(from)))
                .and(MEDIA_FACT.UPLOADED_AT.lt(at(to)))
                .and(MEDIA_FACT.TIME_TO_READY_MS.isNotNull())
                .fetchSingle()
                .value1();
        return p == null ? new Percentiles(null, null) : new Percentiles(p[0], p[1]);
    }

    /**
     * Served For You items per period and UK day, and how many were positive.
     * Expected: served_item's partition key (served_at) prunes both periods' partitions.
     */
    List<ServeDay> serves(Instant previousFrom, Instant from, Instant to) {
        return dsl.select(
                        DSL.field(SERVE_FACT.SERVED_AT.ge(at(from))),
                        londonDay(SERVE_FACT.SERVED_AT),
                        DSL.count(),
                        DSL.count().filterWhere(SERVE_FACT.POSITIVE.isTrue()))
                .from(SERVE_FACT)
                .where(served(previousFrom, to))
                .groupBy(FIRST, SECOND)
                .fetch(r -> new ServeDay(r.value1(), r.value2(), r.value3(), r.value4()));
    }

    // ── Feed engagement (For You only: Following logs no served items) ─────────────────────────────

    record Rate(long served, long positive) {}

    record PositionRate(int position, long served, long positive) {}

    record ConfigRate(int version, @Nullable Instant activatedAt, long served, long positive) {}

    record WatchStats(int bucket, long watched, @Nullable Double meanWatch, long completed) {}

    /**
     * Served and positive items by logged position (0 = top), for positions below {@code positions}.
     * Expected: served_item's partition key prunes the period.
     */
    List<PositionRate> byPosition(Instant from, Instant to, int positions) {
        return dsl.select(SERVE_FACT.POSITION, DSL.count(), DSL.count().filterWhere(SERVE_FACT.POSITIVE.isTrue()))
                .from(SERVE_FACT)
                .where(served(from, to))
                .and(SERVE_FACT.POSITION.lt(positions))
                .groupBy(SERVE_FACT.POSITION)
                .orderBy(SERVE_FACT.POSITION)
                .fetch(r -> new PositionRate(r.value1(), r.value2(), r.value3()));
    }

    /**
     * Served and positive items by the version of the config that served them, oldest first.
     * Expected: served_item's partition key prunes the period; ranking_config by its primary key.
     */
    List<ConfigRate> byConfig(Instant from, Instant to) {
        return dsl.select(
                        SERVE_FACT.CONFIG_VERSION,
                        SERVE_FACT.CONFIG_ACTIVATED_AT,
                        DSL.count(),
                        DSL.count().filterWhere(SERVE_FACT.POSITIVE.isTrue()))
                .from(SERVE_FACT)
                .where(served(from, to))
                .groupBy(SERVE_FACT.CONFIG_VERSION, SERVE_FACT.CONFIG_ACTIVATED_AT)
                .orderBy(SERVE_FACT.CONFIG_VERSION)
                .fetch(r -> new ConfigRate(
                        r.value1(), r.value2() == null ? null : r.value2().toInstant(), r.value3(), r.value4()));
    }

    /** Exploration slots (true) and ranked slots (false). Expected: served_item's partition key. */
    List<Record3<Boolean, Integer, Integer>> byExploration(Instant from, Instant to) {
        return dsl.select(SERVE_FACT.EXPLORATION, DSL.count(), DSL.count().filterWhere(SERVE_FACT.POSITIVE.isTrue()))
                .from(SERVE_FACT)
                .where(served(from, to))
                .groupBy(SERVE_FACT.EXPLORATION)
                .fetch();
    }

    /**
     * Served items with a watch, by tenth of the furthest watch fraction (bucket 1 = [0, 0.1) … 10 =
     * [0.9, 1.0]; a complete watch, 1.0, falls in the last). Items never watched are left out here (they
     * still count as served everywhere else). Expected: served_item's partition key.
     */
    List<Record2<Integer, Integer>> watchHistogram(Instant from, Instant to) {
        Field<Integer> bin =
                DSL.field("least(width_bucket({0}, 0, 1, 10), 10)", Integer.class, SERVE_FACT.MAX_WATCH_FRACTION);
        return dsl.select(bin, DSL.count())
                .from(SERVE_FACT)
                .where(served(from, to))
                .and(SERVE_FACT.MAX_WATCH_FRACTION.isNotNull())
                .groupBy(FIRST)
                .orderBy(FIRST)
                .fetch();
    }

    /**
     * Watched served items whose post opens with a video, by that first clip's length: how many, the mean
     * furthest watch, and how many were watched to the end. A watch event names the post, not the clip,
     * so the first clip's length stands in for the post's.
     * Buckets: 0 under 15 s, 1 15–30 s, 2 30–60 s, 3 60–120 s, 4 120–180 s, 5 180 s and over.
     * Expected: served_item's partition key; each post by content.post's primary key, then post_clip_post.
     */
    List<WatchStats> watchByClipLength(Instant from, Instant to) {
        Field<Integer> ms = POST_FACT.FIRST_CLIP_DURATION_MS;
        Field<Integer> bucket = DSL.when(ms.lt(15_000), 0)
                .when(ms.lt(30_000), 1)
                .when(ms.lt(60_000), 2)
                .when(ms.lt(120_000), 3)
                .when(ms.lt(180_000), 4)
                .otherwise(5);
        return dsl.select(
                        bucket,
                        DSL.count(),
                        DSL.avg(SERVE_FACT.MAX_WATCH_FRACTION),
                        DSL.count().filterWhere(SERVE_FACT.MAX_WATCH_FRACTION.ge(1.0f)))
                .from(SERVE_FACT)
                .join(POST_FACT)
                .on(POST_FACT.POST_ID.eq(SERVE_FACT.POST_ID))
                .where(served(from, to))
                .and(SERVE_FACT.MAX_WATCH_FRACTION.isNotNull())
                .and(POST_FACT.FIRST_CLIP_KIND.eq("VIDEO"))
                .and(ms.isNotNull())
                .groupBy(FIRST)
                .orderBy(FIRST)
                .fetch(r -> new WatchStats(
                        r.value1(),
                        r.value2(),
                        r.value3() == null ? null : r.value3().doubleValue(),
                        r.value4()));
    }

    /**
     * Served and positive items by the kind of the post's first clip (VIDEO, or IMAGE for a photo).
     * Expected: served_item's partition key; each post by content.post's primary key, then post_clip_post.
     */
    List<Record3<String, Integer, Integer>> byFirstClipKind(Instant from, Instant to) {
        return dsl.select(POST_FACT.FIRST_CLIP_KIND, DSL.count(), DSL.count().filterWhere(SERVE_FACT.POSITIVE.isTrue()))
                .from(SERVE_FACT)
                .join(POST_FACT)
                .on(POST_FACT.POST_ID.eq(SERVE_FACT.POST_ID))
                .where(served(from, to))
                .and(POST_FACT.FIRST_CLIP_KIND.isNotNull())
                .groupBy(POST_FACT.FIRST_CLIP_KIND)
                .fetch();
    }

    // ── Content ──────────────────────────────────────────────────────────────────────────────────

    record TopRow(
            UUID id, String name, @Nullable Instant startsAt, @Nullable String venue, long posts, long positive) {}

    /**
     * The artists with the most positive engagements in the period, then the most posts published in it
     * (a post counts once per act on its clips; an artist needs one or the other to be listed).
     * Expected: post_published_at (V138) and post_clip_post for the posts; served_item's partition key and
     * post_clip_post for the positives; the artist by its primary key.
     */
    List<TopRow> topArtists(Instant from, Instant to, int limit) {
        CommonTableExpression<Record2<UUID, Integer>> posts = DSL.name("artist_posts")
                .fields("artist_id", "posts")
                .as(DSL.select(POST_CLIP.ARTIST_ID, DSL.countDistinct(POST_FACT.POST_ID))
                        .from(POST_FACT)
                        .join(POST_CLIP)
                        .on(POST_CLIP.POST_ID.eq(POST_FACT.POST_ID))
                        .where(published(from, to))
                        .and(POST_CLIP.ARTIST_ID.isNotNull())
                        .groupBy(POST_CLIP.ARTIST_ID));
        // One row per (post, act), so a post with two clips of one act counts once for it.
        var acts = DSL.selectDistinct(POST_CLIP.POST_ID, POST_CLIP.ARTIST_ID)
                .from(POST_CLIP)
                .where(POST_CLIP.ARTIST_ID.isNotNull())
                .asTable("acts");
        CommonTableExpression<Record2<UUID, Integer>> positives = DSL.name("artist_positives")
                .fields("artist_id", "positive")
                .as(DSL.select(acts.field(POST_CLIP.ARTIST_ID), DSL.count())
                        .from(SERVE_FACT)
                        .join(acts)
                        .on(acts.field(POST_CLIP.POST_ID).eq(SERVE_FACT.POST_ID))
                        .where(served(from, to))
                        .and(SERVE_FACT.POSITIVE.isTrue())
                        .groupBy(acts.field(POST_CLIP.ARTIST_ID)));
        Field<UUID> postsId = posts.field("artist_id", UUID.class);
        Field<UUID> positivesId = positives.field("artist_id", UUID.class);
        return dsl.with(posts, positives)
                .select(
                        ARTIST.ID,
                        ARTIST.NAME,
                        DSL.coalesce(posts.field("posts", Integer.class), 0),
                        DSL.coalesce(positives.field("positive", Integer.class), 0))
                .from(posts)
                .fullJoin(positives)
                .on(positivesId.eq(postsId))
                .join(ARTIST)
                .on(ARTIST.ID.eq(DSL.coalesce(postsId, positivesId)))
                .orderBy(DSL.inline(4).desc(), DSL.inline(3).desc(), ARTIST.NAME)
                .limit(limit)
                .fetch(r -> new TopRow(r.value1(), r.value2(), null, null, r.value3(), r.value4()));
    }

    /**
     * The gigs with the most positive engagements in the period, then the most posts published in it.
     * Expected: post_published_at (V138) for the posts; served_item's partition key and content.post's
     * primary key for the positives; the gig and its venue by primary key.
     */
    List<TopRow> topGigs(Instant from, Instant to, int limit) {
        CommonTableExpression<Record2<UUID, Integer>> posts = DSL.name("gig_posts")
                .fields("gig_id", "posts")
                .as(DSL.select(POST_FACT.GIG_ID, DSL.count())
                        .from(POST_FACT)
                        .where(published(from, to))
                        .and(POST_FACT.GIG_ID.isNotNull())
                        .groupBy(POST_FACT.GIG_ID));
        CommonTableExpression<Record2<UUID, Integer>> positives = DSL.name("gig_positives")
                .fields("gig_id", "positive")
                .as(DSL.select(POST_FACT.GIG_ID, DSL.count())
                        .from(SERVE_FACT)
                        .join(POST_FACT)
                        .on(POST_FACT.POST_ID.eq(SERVE_FACT.POST_ID))
                        .where(served(from, to))
                        .and(SERVE_FACT.POSITIVE.isTrue())
                        .and(POST_FACT.GIG_ID.isNotNull())
                        .groupBy(POST_FACT.GIG_ID));
        Field<UUID> postsId = posts.field("gig_id", UUID.class);
        Field<UUID> positivesId = positives.field("gig_id", UUID.class);
        return dsl.with(posts, positives)
                .select(
                        GIG.ID,
                        GIG.TITLE,
                        GIG.STARTS_AT,
                        VENUE.NAME,
                        DSL.coalesce(posts.field("posts", Integer.class), 0),
                        DSL.coalesce(positives.field("positive", Integer.class), 0))
                .from(posts)
                .fullJoin(positives)
                .on(positivesId.eq(postsId))
                .join(GIG)
                .on(GIG.ID.eq(DSL.coalesce(postsId, positivesId)))
                .join(VENUE)
                .on(VENUE.ID.eq(GIG.VENUE_ID))
                .orderBy(DSL.inline(6).desc(), DSL.inline(5).desc(), GIG.STARTS_AT.desc())
                .limit(limit)
                .fetch(r ->
                        new TopRow(r.value1(), r.value2(), r.value3().toInstant(), r.value4(), r.value5(), r.value6()));
    }

    /** Completed uploads by hour of the UK day (0–23). Expected: media_asset_uploaded_at (V138). */
    List<Record2<Integer, Integer>> uploadsByHour(Instant from, Instant to) {
        Field<Integer> hour = DSL.field(
                "extract(hour from {0} at time zone 'Europe/London')::int", Integer.class, MEDIA_FACT.UPLOADED_AT);
        return dsl.select(hour, DSL.count())
                .from(MEDIA_FACT)
                .where(MEDIA_FACT.UPLOADED_AT.ge(at(from)))
                .and(MEDIA_FACT.UPLOADED_AT.lt(at(to)))
                .groupBy(FIRST)
                .orderBy(FIRST)
                .fetch();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

    /** True for an account at a synthetic domain, false otherwise (also when there is no account row). */
    private static Field<Boolean> synthetic(List<String> syntheticDomains) {
        return DSL.coalesce(
                DSL.field(USER_FACT.EMAIL_DOMAIN.eq(DSL.any(syntheticDomains.toArray(String[]::new)))),
                DSL.inline(false));
    }

    private static Condition window(Instant from, Instant to) {
        return INTERACTION_EVENT.OCCURRED_AT.ge(at(from)).and(INTERACTION_EVENT.OCCURRED_AT.lt(at(to)));
    }

    private static Condition served(Instant from, Instant to) {
        return SERVE_FACT.SERVED_AT.ge(at(from)).and(SERVE_FACT.SERVED_AT.lt(at(to)));
    }

    private static Condition published(Instant from, Instant to) {
        return POST_FACT.PUBLISHED_AT.ge(at(from)).and(POST_FACT.PUBLISHED_AT.lt(at(to)));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
