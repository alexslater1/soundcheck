package app.soundcheck.analytics.internal;

import app.soundcheck.analytics.internal.AnalyticsQueries.ActivityDay;
import app.soundcheck.analytics.internal.AnalyticsQueries.CountDay;
import app.soundcheck.analytics.internal.AnalyticsQueries.PeriodDay;
import app.soundcheck.analytics.internal.AnalyticsQueries.ServeDay;
import app.soundcheck.analytics.internal.AnalyticsQueries.UploadDay;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.config.SyntheticAccounts;
import app.soundcheck.shared.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongFunction;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The console's overview: KPIs against the previous period, a daily series, the share
 * of the activity that synthetic accounts produced, feed engagement and the top artists and gigs. The SQL
 * is in {@link AnalyticsQueries}; this class checks the period, works out the previous one and the UK days,
 * and turns counts into rates. Read-only; operators only (API role).
 *
 * <p>A period is [{@code from}, {@code to}) of at most 90 days; the previous period is the same length
 * just before it, [{@code from - (to - from)}, {@code from}). Days are UK days ({@code Europe/London}).
 */
@Service
@ConditionalOnRole(Roles.API)
@Transactional(readOnly = true)
public class AdminAnalytics {

    static final Duration MAX_PERIOD = Duration.ofDays(90);
    static final ZoneId LONDON = ZoneId.of("Europe/London");
    /** The position curve covers the first five pages (logged positions 0–49). */
    static final int POSITIONS = 50;

    /** The most artists and gigs the content lists return; the console shows 10 and scrolls the rest. */
    public static final int MAX_TOP = 50;

    private static final double DAY_MS = Duration.ofDays(1).toMillis();

    /** A headline number now and in the previous period; null when there was nothing to measure. */
    @Schema(name = "AnalyticsKpi")
    public record Kpi(@Schema(nullable = true) Double value, @Schema(nullable = true) Double previous) {}

    /**
     * @param dau               user-days with at least one event, divided by the period's length in days
     * @param wau               distinct users with an event in the 7 days up to the period's end
     * @param newUsers          accounts created in the period
     * @param postsPerDay       posts published in the period (whatever their status now), per day
     * @param uploadsPerDay     uploads completed in the period, per day
     * @param processingSuccess of the period's uploads that finished processing, the share that became READY
     *     (FAILED and REJECTED count against it)
     * @param timeToReadyP50Ms  median of ready_at - uploaded_at over the period's uploads that became READY
     * @param timeToReadyP95Ms  95th percentile of the same
     * @param feedEngagement    positive For You items divided by served ones
     */
    @Schema(name = "AnalyticsKpis")
    public record Kpis(
            Kpi dau,
            Kpi wau,
            Kpi newUsers,
            Kpi postsPerDay,
            Kpi uploadsPerDay,
            Kpi processingSuccess,
            Kpi timeToReadyP50Ms,
            Kpi timeToReadyP95Ms,
            Kpi feedEngagement) {}

    /**
     * One UK day of the period (the first and last may be partial).
     *
     * @param wau the distinct users active on this day or the six before it
     */
    @Schema(name = "AnalyticsDay")
    public record Day(
            LocalDate day,
            long dauReal,
            long dauSynthetic,
            long wau,
            long newUsers,
            long posts,
            long uploads,
            @Schema(nullable = true) Double processingSuccess,
            @Schema(nullable = true) Double timeToReadyP50Ms,
            long served,
            long positive) {}

    /**
     * How much of the period's activity synthetic accounts produced: their events plus their posts, over
     * all events plus posts. {@code share} is null when there was no activity at all.
     */
    @Schema(name = "AnalyticsSyntheticShare")
    public record SyntheticShare(
            long events,
            long syntheticEvents,
            long posts,
            long syntheticPosts,
            @Schema(nullable = true) Double share) {}

    @Schema(name = "AnalyticsOverview")
    public record Overview(
            Instant from,
            Instant to,
            Instant previousFrom,
            double days,
            Kpis kpis,
            List<Day> series,
            SyntheticShare syntheticShare,
            List<String> syntheticDomains) {}

    /** Served For You items and the positive ones; {@code rate} is null when nothing was served. */
    @Schema(name = "AnalyticsRate")
    public record Rate(long served, long positive, @Schema(nullable = true) Double rate) {}

    /** @param position the logged position, 0 for the top of the feed */
    @Schema(name = "AnalyticsPositionRate")
    public record PositionRate(int position, long served, long positive, @Schema(nullable = true) Double rate) {}

    @Schema(name = "AnalyticsConfigRate")
    public record ConfigRate(
            int version,
            @Schema(nullable = true) Instant activatedAt,
            long served,
            long positive,
            @Schema(nullable = true) Double rate) {}

    /** Served items whose furthest watch fraction was in [{@code from}, {@code to}) ([0.9, 1.0] for the last). */
    @Schema(name = "AnalyticsWatchBin")
    public record WatchBin(double from, double to, long count) {}

    /**
     * Watched items whose post opens with a video of this length.
     *
     * @param toSeconds      null for the open-ended last bucket
     * @param completionRate the share watched to the end (COMPLETE, or the clip looping)
     */
    @Schema(name = "AnalyticsClipLengthWatch")
    public record ClipLengthWatch(
            String label,
            int fromSeconds,
            @Schema(nullable = true) Integer toSeconds,
            long watched,
            @Schema(nullable = true) Double meanWatchFraction,
            @Schema(nullable = true) Double completionRate) {}

    /**
     * For You engagement in a period (Following logs no served items).
     *
     * @param watchByClipLength by the length of the post's FIRST clip: a watch event names the post, not
     *     the clip it was on
     * @param photo             posts that open with a photo (an IMAGE clip)
     * @param video             posts that open with a video
     */
    @Schema(name = "AnalyticsFeed")
    public record FeedEngagement(
            Instant from,
            Instant to,
            Rate overall,
            List<PositionRate> byPosition,
            List<ConfigRate> byConfig,
            Rate exploration,
            Rate exploitation,
            List<WatchBin> watchHistogram,
            List<ClipLengthWatch> watchByClipLength,
            Rate photo,
            Rate video) {}

    @Schema(name = "AnalyticsTopArtist")
    public record TopArtist(UUID artistId, String name, long posts, long positive) {}

    @Schema(name = "AnalyticsTopGig")
    public record TopGig(UUID gigId, String title, Instant startsAt, String venue, long posts, long positive) {}

    /** @param hour 0–23, UK time */
    @Schema(name = "AnalyticsHourCount")
    public record HourCount(int hour, long uploads) {}

    @Schema(name = "AnalyticsContent")
    public record ContentStats(
            Instant from,
            Instant to,
            List<TopArtist> topArtists,
            List<TopGig> topGigs,
            List<HourCount> uploadsByHour) {}

    /** The clip-length buckets of {@link AnalyticsQueries#watchByClipLength}, in its bucket order. */
    static final List<ClipLengthWatch> CLIP_LENGTHS = List.of(
            new ClipLengthWatch("< 15 s", 0, 15, 0, null, null),
            new ClipLengthWatch("15–30 s", 15, 30, 0, null, null),
            new ClipLengthWatch("30–60 s", 30, 60, 0, null, null),
            new ClipLengthWatch("1–2 min", 60, 120, 0, null, null),
            new ClipLengthWatch("2–3 min", 120, 180, 0, null, null),
            new ClipLengthWatch("> 3 min", 180, null, 0, null, null));

    private final AnalyticsQueries queries;
    private final SyntheticAccounts synthetic;

    AdminAnalytics(AnalyticsQueries queries, SyntheticAccounts synthetic) {
        this.queries = queries;
        this.synthetic = synthetic;
    }

    /** @throws ApiException 400 {@code ANALYTICS_RANGE_INVALID} unless {@code from < to} and the period is ≤ 90 days */
    public Overview overview(Instant from, Instant to) {
        check(from, to);
        Instant previousFrom = from.minus(Duration.between(from, to));
        double days = Duration.between(from, to).toMillis() / DAY_MS;
        List<String> domains = synthetic.domains();

        List<ActivityDay> activity = queries.activity(previousFrom, from, to, domains);
        List<CountDay> users = queries.newUsers(previousFrom, from, to, domains);
        List<CountDay> posts = queries.posts(previousFrom, from, to, domains);
        List<UploadDay> uploads = queries.uploads(previousFrom, from, to);
        List<ServeDay> serves = queries.serves(previousFrom, from, to);
        Record2<Integer, Integer> wau = queries.weeklyActive(to, from);
        AnalyticsQueries.Percentiles ready = queries.timeToReady(from, to);
        AnalyticsQueries.Percentiles readyBefore = queries.timeToReady(previousFrom, from);

        LocalDate firstDay = day(from);
        LocalDate lastDay = day(to.minusNanos(1));
        Map<LocalDate, Integer> wauByDay = new HashMap<>();
        queries.weeklyActiveByDay(firstDay, lastDay, from, to).forEach(r -> wauByDay.put(r.value1(), r.value2()));

        Kpis kpis = new Kpis(
                new Kpi(
                        sum(activity, true, a -> a.real() + a.synthetic()) / days,
                        sum(activity, false, a -> a.real() + a.synthetic()) / days),
                new Kpi((double) wau.value1(), (double) wau.value2()),
                new Kpi((double) sum(users, true, CountDay::count), (double) sum(users, false, CountDay::count)),
                new Kpi(sum(posts, true, CountDay::count) / days, sum(posts, false, CountDay::count) / days),
                new Kpi(sum(uploads, true, UploadDay::uploads) / days, sum(uploads, false, UploadDay::uploads) / days),
                new Kpi(success(uploads, true), success(uploads, false)),
                new Kpi(ready.p50(), readyBefore.p50()),
                new Kpi(ready.p95(), readyBefore.p95()),
                new Kpi(
                        ratio(sum(serves, true, ServeDay::positive), sum(serves, true, ServeDay::served)),
                        ratio(sum(serves, false, ServeDay::positive), sum(serves, false, ServeDay::served))));

        Map<LocalDate, ActivityDay> activityByDay = currentByDay(activity);
        Map<LocalDate, CountDay> usersByDay = currentByDay(users);
        Map<LocalDate, CountDay> postsByDay = currentByDay(posts);
        Map<LocalDate, UploadDay> uploadsByDay = currentByDay(uploads);
        Map<LocalDate, ServeDay> servesByDay = currentByDay(serves);
        List<Day> series = new ArrayList<>();
        for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
            ActivityDay a = activityByDay.get(d);
            CountDay u = usersByDay.get(d);
            CountDay p = postsByDay.get(d);
            UploadDay m = uploadsByDay.get(d);
            ServeDay s = servesByDay.get(d);
            series.add(new Day(
                    d,
                    a == null ? 0 : a.real(),
                    a == null ? 0 : a.synthetic(),
                    wauByDay.getOrDefault(d, 0),
                    u == null ? 0 : u.count(),
                    p == null ? 0 : p.count(),
                    m == null ? 0 : m.uploads(),
                    m == null ? null : ratio(m.ready(), m.ready() + m.failed()),
                    m == null ? null : m.p50Ms(),
                    s == null ? 0 : s.served(),
                    s == null ? 0 : s.positive()));
        }

        long events = sum(activity, true, ActivityDay::events);
        long syntheticEvents = sum(activity, true, ActivityDay::syntheticEvents);
        long postCount = sum(posts, true, CountDay::count);
        long syntheticPosts = sum(posts, true, CountDay::synthetic);
        SyntheticShare share = new SyntheticShare(
                events,
                syntheticEvents,
                postCount,
                syntheticPosts,
                ratio(syntheticEvents + syntheticPosts, events + postCount));

        return new Overview(from, to, previousFrom, days, kpis, series, share, domains);
    }

    /** @throws ApiException 400 {@code ANALYTICS_RANGE_INVALID} as {@link #overview} */
    public FeedEngagement feed(Instant from, Instant to) {
        check(from, to);
        List<PositionRate> byPosition = queries.byPosition(from, to, POSITIONS).stream()
                .map(p -> new PositionRate(p.position(), p.served(), p.positive(), ratio(p.positive(), p.served())))
                .toList();
        List<ConfigRate> byConfig = queries.byConfig(from, to).stream()
                .map(c -> new ConfigRate(
                        c.version(), c.activatedAt(), c.served(), c.positive(), ratio(c.positive(), c.served())))
                .toList();

        Rate exploration = rate(0, 0);
        Rate exploitation = rate(0, 0);
        for (Record3<Boolean, Integer, Integer> r : queries.byExploration(from, to)) {
            if (Boolean.TRUE.equals(r.value1())) {
                exploration = rate(r.value2(), r.value3());
            } else {
                exploitation = rate(r.value2(), r.value3());
            }
        }
        Rate overall =
                rate(exploration.served() + exploitation.served(), exploration.positive() + exploitation.positive());

        long[] bins = new long[10];
        for (Record2<Integer, Integer> r : queries.watchHistogram(from, to)) {
            bins[r.value1() - 1] = r.value2();
        }
        List<WatchBin> histogram = new ArrayList<>();
        for (int i = 0; i < bins.length; i++) {
            histogram.add(new WatchBin(i / 10.0, (i + 1) / 10.0, bins[i]));
        }

        List<ClipLengthWatch> byLength = new ArrayList<>(CLIP_LENGTHS);
        for (AnalyticsQueries.WatchStats w : queries.watchByClipLength(from, to)) {
            ClipLengthWatch b = CLIP_LENGTHS.get(w.bucket());
            byLength.set(
                    w.bucket(),
                    new ClipLengthWatch(
                            b.label(),
                            b.fromSeconds(),
                            b.toSeconds(),
                            w.watched(),
                            w.meanWatch(),
                            ratio(w.completed(), w.watched())));
        }

        Rate photo = rate(0, 0);
        Rate video = rate(0, 0);
        for (Record3<String, Integer, Integer> r : queries.byFirstClipKind(from, to)) {
            if ("IMAGE".equals(r.value1())) {
                photo = rate(r.value2(), r.value3());
            } else if ("VIDEO".equals(r.value1())) {
                video = rate(r.value2(), r.value3());
            }
        }
        return new FeedEngagement(
                from, to, overall, byPosition, byConfig, exploration, exploitation, histogram, byLength, photo, video);
    }

    /** @throws ApiException 400 {@code ANALYTICS_RANGE_INVALID} as {@link #overview} */
    public ContentStats content(Instant from, Instant to, int top) {
        check(from, to);
        List<TopArtist> artists = queries.topArtists(from, to, top).stream()
                .map(r -> new TopArtist(r.id(), r.name(), r.posts(), r.positive()))
                .toList();
        List<TopGig> gigs = queries.topGigs(from, to, top).stream()
                .map(r -> new TopGig(
                        r.id(), r.name(), r.startsAt(), r.venue() == null ? "" : r.venue(), r.posts(), r.positive()))
                .toList();
        long[] hours = new long[24];
        for (Record2<Integer, Integer> r : queries.uploadsByHour(from, to)) {
            hours[r.value1()] = r.value2();
        }
        List<HourCount> byHour = new ArrayList<>();
        for (int h = 0; h < hours.length; h++) {
            byHour.add(new HourCount(h, hours[h]));
        }
        return new ContentStats(from, to, artists, gigs, byHour);
    }

    static void check(Instant from, Instant to) {
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
            throw ApiException.badRequest(
                    "ANALYTICS_RANGE_INVALID", "The period must start before it ends and be at most 90 days long");
        }
    }

    static LocalDate day(Instant at) {
        return at.atZone(LONDON).toLocalDate();
    }

    static @Nullable Double ratio(long part, long whole) {
        return whole == 0 ? null : (double) part / whole;
    }

    private static Rate rate(long served, long positive) {
        return new Rate(served, positive, ratio(positive, served));
    }

    private static @Nullable Double success(List<UploadDay> uploads, boolean current) {
        long ready = sum(uploads, current, UploadDay::ready);
        return ratio(ready, ready + sum(uploads, current, UploadDay::failed));
    }

    /** The total of one count over the days of one period. */
    private static <T extends PeriodDay> long sum(List<T> rows, boolean current, ToLongFunction<T> value) {
        return rows.stream()
                .filter(r -> r.current() == current)
                .mapToLong(value)
                .sum();
    }

    /** The current period's rows by day (a day appears once per period). */
    private static <T extends PeriodDay> Map<LocalDate, T> currentByDay(List<T> rows) {
        Map<LocalDate, T> map = new HashMap<>();
        rows.stream().filter(PeriodDay::current).forEach(r -> map.put(r.day(), r));
        return map;
    }
}
