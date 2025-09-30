package app.soundcheck.analytics;

import static app.soundcheck.analytics.AnalyticsFixture.SEED_CONFIG;
import static app.soundcheck.analytics.AnalyticsFixture.SEED_DOMAIN;
import static app.soundcheck.analytics.AnalyticsFixture.SIM_DOMAIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.soundcheck.shared.Ids;
import app.soundcheck.shared.partitions.DailyPartitions;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The console overview's three endpoints on hand-made rows, with every expected number
 * worked out in the comments. The period is two UK days in summer (BST, so a UK day starts at 23:00 UTC
 * the day before): from 2031-06-10T23:00Z to 2031-06-12T23:00Z, i.e. UK days 11 and 12 June; the previous
 * period is UK days 9 and 10 June.
 */
class AdminAnalyticsIT extends IntegrationTest {

    static final Instant FROM = Instant.parse("2031-06-10T23:00:00Z");
    static final Instant TO = Instant.parse("2031-06-12T23:00:00Z");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DSLContext dsl;

    @Autowired
    DailyPartitions partitions;

    TestApi api;
    AnalyticsFixture fixture;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        fixture = new AnalyticsFixture(dsl, partitions, LocalDate.parse("2031-06-08"), LocalDate.parse("2031-07-01"));
    }

    @AfterEach
    void tearDown() {
        fixture.clean();
    }

    /** The overview's fixture: what it holds is listed in each test that reads it. */
    record Overview(UUID a1, UUID a2, UUID g1, UUID g2) {}

    Overview seedOverview() {
        // Accounts: R1, R2 real; S1 a seed persona, S2 a simulator persona. S2 joined at 00:30 UK time on
        // 11 June (23:30 UTC on the 10th): the current period, UK day 11 June.
        UUID r1 = fixture.user("example.com", at("2031-06-11T10:00:00Z"));
        UUID r2 = fixture.user("example.com", at("2031-06-09T10:00:00Z"));
        UUID s1 = fixture.user(SEED_DOMAIN, at("2031-06-12T22:30:00Z"));
        UUID s2 = fixture.user(SIM_DOMAIN, at("2031-06-10T23:30:00Z"));

        // Uploads (ready after): now M1 1000 ms, M2 3000 ms, M3 500 ms, M4 FAILED, M5 still processing;
        // before M6 2000 ms, M7 REJECTED. M8 was never uploaded, so it is not an upload at all.
        UUID m1 = fixture.media(r1, "VIDEO", "READY", at("2031-06-11T10:00:00Z"), 1_000L, 10_000);
        UUID m2 = fixture.media(s1, "VIDEO", "READY", at("2031-06-11T11:00:00Z"), 3_000L, 45_000);
        UUID m3 = fixture.media(s1, "IMAGE", "READY", at("2031-06-12T10:00:00Z"), 500L, null);
        fixture.media(r1, "VIDEO", "FAILED", at("2031-06-12T12:00:00Z"), null, null);
        fixture.media(r1, "VIDEO", "PROCESSING", at("2031-06-12T13:00:00Z"), null, null);
        UUID m6 = fixture.media(r2, "VIDEO", "READY", at("2031-06-10T10:00:00Z"), 2_000L, 20_000);
        fixture.media(r2, "VIDEO", "REJECTED", at("2031-06-09T10:00:00Z"), null, null);
        fixture.media(r1, "VIDEO", "AWAITING_UPLOAD", null, null, null);

        // Posts: now P1 (R1, gig G1, act A1), P2 (S1, gig G2, two clips of A2), P3 (S2, since deleted);
        // before P4 (R2, A1). P5 was never published.
        UUID a1 = fixture.artist("Analytics Act One");
        UUID a2 = fixture.artist("Analytics Act Two");
        UUID g1 = fixture.gig("Analytics Gig One");
        UUID g2 = fixture.gig("Analytics Gig Two");
        UUID p1 = fixture.post(r1, "PUBLISHED", at("2031-06-11T12:00:00Z"), g1);
        fixture.clip(p1, r1, 0, m1, a1);
        UUID p2 = fixture.post(s1, "PUBLISHED", at("2031-06-11T13:00:00Z"), g2);
        fixture.clip(p2, s1, 0, m3, a2);
        fixture.clip(p2, s1, 1, m2, a2);
        fixture.post(s2, "DELETED", at("2031-06-12T10:00:00Z"), null);
        UUID p4 = fixture.post(r2, "PUBLISHED", at("2031-06-09T12:00:00Z"), null);
        fixture.clip(p4, r2, 0, m6, a1);
        fixture.post(r1, "PENDING_MEDIA", null, null);

        // Served: now R1 four items (P1 liked: 1 of 4); before R2 two items (P4 liked: 1 of 2).
        UUID x1 = fixture.session();
        Instant x1At = at("2031-06-11T12:00:00Z");
        fixture.serve(x1, 0, x1At, r1, p1, SEED_CONFIG, false);
        for (int position = 1; position < 4; position++) {
            fixture.serve(x1, position, x1At, r1, Ids.newId(), SEED_CONFIG, false);
        }
        UUID x0 = fixture.session();
        Instant x0At = at("2031-06-10T12:00:00Z");
        fixture.serve(x0, 0, x0At, r2, p4, SEED_CONFIG, false);
        fixture.serve(x0, 1, x0At, r2, Ids.newId(), SEED_CONFIG, false);

        // Events now (6, 3 of them synthetic): UK 11 June R1 and S2 (twice); 12 June R1, R2 and S1 (23:45
        // UK time). Before (3): R2 on 9 June, and twice on 10 June, the last a minute before the period.
        fixture.event(r1, p1, x1, "LIKE", null, at("2031-06-11T12:00:30Z"));
        fixture.event(r1, Ids.newId(), null, "IMPRESSION", null, at("2031-06-12T12:00:00Z"));
        fixture.event(r2, Ids.newId(), null, "IMPRESSION", null, at("2031-06-12T08:00:00Z"));
        fixture.event(s1, Ids.newId(), null, "IMPRESSION", null, at("2031-06-12T22:45:00Z"));
        fixture.event(s2, Ids.newId(), null, "IMPRESSION", null, at("2031-06-11T00:15:00Z"));
        fixture.event(s2, Ids.newId(), null, "IMPRESSION", null, at("2031-06-11T09:00:00Z"));
        fixture.event(r2, Ids.newId(), null, "IMPRESSION", null, at("2031-06-09T12:00:00Z"));
        fixture.event(r2, p4, x0, "LIKE", null, at("2031-06-10T12:00:30Z"));
        fixture.event(r2, Ids.newId(), null, "IMPRESSION", null, at("2031-06-10T22:59:00Z"));
        return new Overview(a1, a2, g1, g2);
    }

    @Test
    void theOverviewsKpisAreThoseWorkedOutByHand() {
        seedOverview();

        JsonNode body = ok(get("overview", FROM, TO));

        assertThat(body.path("days").asDouble()).isEqualTo(2.0);
        assertThat(body.path("previousFrom").asText("")).isEqualTo("2031-06-08T23:00:00Z");
        JsonNode k = body.path("kpis");
        // DAU: user-days now 11 June R1, S2; 12 June R1, R2, S1 = 5 over 2 days; before 9 June R2, 10 June R2.
        assertKpi(k.path("dau"), 2.5, 1.0);
        // WAU: the 7 days up to the end: R1, R2, S1, S2; up to the start: R2 only.
        assertKpi(k.path("wau"), 4, 1);
        assertKpi(k.path("newUsers"), 3, 1);
        assertKpi(k.path("postsPerDay"), 1.5, 0.5);
        assertKpi(k.path("uploadsPerDay"), 2.5, 1.0);
        // Ready of finished: now 3 of 4 (M4 failed, M5 unfinished); before 1 of 2 (M7 rejected).
        assertKpi(k.path("processingSuccess"), 0.75, 0.5);
        // Time to ready now 500, 1000, 3000 ms: median 1000; p95 = 1000 + 0.9 × 2000 = 2800.
        assertKpi(k.path("timeToReadyP50Ms"), 1000, 2000);
        assertKpi(k.path("timeToReadyP95Ms"), 2800, 2000);
        assertKpi(k.path("feedEngagement"), 0.25, 0.5);

        // Synthetic share: (3 events + 2 posts) of (6 events + 3 posts).
        JsonNode share = body.path("syntheticShare");
        assertThat(share.path("events").asLong()).isEqualTo(6);
        assertThat(share.path("syntheticEvents").asLong()).isEqualTo(3);
        assertThat(share.path("posts").asLong()).isEqualTo(3);
        assertThat(share.path("syntheticPosts").asLong()).isEqualTo(2);
        assertThat(share.path("share").asDouble()).isCloseTo(5.0 / 9, within(1e-9));
        assertThat(body.path("syntheticDomains").toString()).contains(SEED_DOMAIN, SIM_DOMAIN);
    }

    @Test
    void theSeriesHasOneRowPerUkDay() {
        seedOverview();

        JsonNode series = ok(get("overview", FROM, TO)).path("series");

        assertThat(series.size()).isEqualTo(2);
        JsonNode first = series.get(0);
        assertThat(first.path("day").asText("")).isEqualTo("2031-06-11");
        assertThat(first.path("dauReal").asLong()).isEqualTo(1); // R1 (R2's 23:59 UK time is the 10th)
        assertThat(first.path("dauSynthetic").asLong()).isEqualTo(1); // S2, from 00:15 UTC = 01:15 UK time
        assertThat(first.path("wau").asLong()).isEqualTo(3); // R2 (9–10 June), R1, S2
        assertThat(first.path("newUsers").asLong()).isEqualTo(2); // R1, S2 (00:30 UK time)
        assertThat(first.path("posts").asLong()).isEqualTo(2);
        assertThat(first.path("uploads").asLong()).isEqualTo(2);
        assertThat(first.path("processingSuccess").asDouble()).isEqualTo(1.0);
        assertThat(first.path("timeToReadyP50Ms").asDouble()).isEqualTo(2000.0); // 1000 and 3000
        assertThat(first.path("served").asLong()).isEqualTo(4);
        assertThat(first.path("positive").asLong()).isEqualTo(1);

        JsonNode second = series.get(1);
        assertThat(second.path("day").asText("")).isEqualTo("2031-06-12");
        assertThat(second.path("dauReal").asLong()).isEqualTo(2);
        assertThat(second.path("dauSynthetic").asLong()).isEqualTo(1); // S1 at 23:45 UK time
        assertThat(second.path("wau").asLong()).isEqualTo(4);
        assertThat(second.path("newUsers").asLong()).isEqualTo(1);
        assertThat(second.path("posts").asLong()).isEqualTo(1);
        assertThat(second.path("uploads").asLong()).isEqualTo(3);
        assertThat(second.path("processingSuccess").asDouble()).isEqualTo(0.5);
        assertThat(second.path("timeToReadyP50Ms").asDouble()).isEqualTo(500.0);
        assertThat(second.path("served").asLong()).isZero();
    }

    @Test
    void anEmptyPeriodHasZerosAndNoRates() {
        Instant from = at("2031-01-01T00:00:00Z");

        JsonNode body = ok(get("overview", from, from.plus(Duration.ofDays(7))));

        JsonNode k = body.path("kpis");
        assertThat(k.path("dau").path("value").asDouble()).isZero();
        assertThat(k.path("newUsers").path("value").asDouble()).isZero();
        assertThat(k.path("processingSuccess").path("value").isNull()).isTrue();
        assertThat(k.path("timeToReadyP50Ms").path("value").isNull()).isTrue();
        assertThat(k.path("feedEngagement").path("value").isNull()).isTrue();
        assertThat(body.path("syntheticShare").path("share").isNull()).isTrue();
        assertThat(body.path("series").size()).isEqualTo(7);
    }

    @Test
    void theContentEndpointRanksArtistsAndGigsAndCountsUploadsByUkHour() {
        Overview o = seedOverview();

        JsonNode body = ok(get("content", FROM, TO));

        // A1: P1 (1 post) liked once; A2: P2 (1 post, two clips) never liked. P4 is from before.
        JsonNode artists = body.path("topArtists");
        assertThat(artists.size()).isEqualTo(2);
        assertThat(artists.get(0).path("artistId").asText("")).isEqualTo(o.a1().toString());
        assertThat(artists.get(0).path("posts").asLong()).isEqualTo(1);
        assertThat(artists.get(0).path("positive").asLong()).isEqualTo(1);
        assertThat(artists.get(1).path("artistId").asText("")).isEqualTo(o.a2().toString());
        assertThat(artists.get(1).path("posts").asLong()).isEqualTo(1);
        assertThat(artists.get(1).path("positive").asLong()).isZero();

        JsonNode gigs = body.path("topGigs");
        assertThat(gigs.size()).isEqualTo(2);
        assertThat(gigs.get(0).path("gigId").asText("")).isEqualTo(o.g1().toString());
        assertThat(gigs.get(0).path("venue").asText("")).isEqualTo("Analytics Gig One Hall");
        assertThat(gigs.get(1).path("gigId").asText("")).isEqualTo(o.g2().toString());

        // Uploads at 10, 11, 10, 12, 13 UTC = 11, 12, 11, 13, 14 UK time (BST).
        JsonNode hours = body.path("uploadsByHour");
        assertThat(hours.size()).isEqualTo(24);
        List<Long> counts = new ArrayList<>();
        hours.forEach(h -> counts.add(h.path("uploads").asLong()));
        assertThat(counts.get(11)).isEqualTo(2);
        assertThat(counts.get(12)).isEqualTo(1);
        assertThat(counts.get(13)).isEqualTo(1);
        assertThat(counts.get(14)).isEqualTo(1);
        assertThat(counts.stream().mapToLong(Long::longValue).sum()).isEqualTo(5);
    }

    @Test
    void theContentListsTakeTopUpToFifty() {
        Overview o = seedOverview();
        String path = "/api/v1/admin/analytics/content?from=" + FROM + "&to=" + TO;

        JsonNode one = ok(api.get(path + "&top=1", api.admin()));
        assertThat(one.path("topArtists").size()).isEqualTo(1);
        assertThat(one.path("topArtists").get(0).path("artistId").asText("")).isEqualTo(o.a1().toString());
        assertThat(one.path("topGigs").size()).isEqualTo(1);

        assertThat(api.get(path + "&top=50", api.admin()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.get(path + "&top=51", api.admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.get(path + "&top=0", api.admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Two sessions on 1 July 2031 (positions, first clip, outcome):
     *
     * <pre>
     *   F1, seed config v1:  0 PV1 (20 s video) watched 0.5 · 1 PI1 (photo, then a video) liked ·
     *                        2 PV2 (20 s) exploration, complete · 3 PL (200 s) watched 0.25 · 4 PI2 (photo) exploration
     *   F2, config vN:       0 PL watched 0.75 (positive) · 1 PV1 hidden · 60 PV2 (past the position curve)
     * </pre>
     */
    @Test
    void theFeedEndpointBreaksEngagementDownAsWorkedOutByHand() {
        UUID author = fixture.user("example.com", at("2031-06-01T00:00:00Z"));
        UUID pv1 = post(author, video(author, 20_000));
        UUID pv2 = post(author, video(author, 20_000));
        UUID pl = post(author, video(author, 200_000));
        UUID pi1 = post(author, image(author), video(author, 30_000));
        UUID pi2 = post(author, image(author));
        int version = 1_000_000 + ThreadLocalRandom.current().nextInt(1_000_000);
        UUID configN = fixture.config(version, at("2031-06-30T09:00:00Z"));
        UUID u1 = Ids.newId();
        UUID u2 = Ids.newId();
        UUID f1 = fixture.session();
        UUID f2 = fixture.session();
        Instant t1 = at("2031-07-01T10:00:00Z");
        Instant t2 = at("2031-07-01T11:00:00Z");
        fixture.serve(f1, 0, t1, u1, pv1, SEED_CONFIG, false);
        fixture.serve(f1, 1, t1, u1, pi1, SEED_CONFIG, false);
        fixture.serve(f1, 2, t1, u1, pv2, SEED_CONFIG, true);
        fixture.serve(f1, 3, t1, u1, pl, SEED_CONFIG, false);
        fixture.serve(f1, 4, t1, u1, pi2, SEED_CONFIG, true);
        fixture.serve(f2, 0, t2, u2, pl, configN, false);
        fixture.serve(f2, 1, t2, u2, pv1, configN, false);
        fixture.serve(f2, 60, t2, u2, pv2, configN, false);
        fixture.event(u1, pv1, f1, "WATCH_PROGRESS", 0.5f, t1.plusSeconds(10));
        fixture.event(u1, pi1, f1, "LIKE", null, t1.plusSeconds(20));
        fixture.event(u1, pv2, f1, "COMPLETE", null, t1.plusSeconds(30));
        fixture.event(u1, pl, f1, "WATCH_PROGRESS", 0.25f, t1.plusSeconds(40));
        fixture.event(u2, pl, f2, "WATCH_PROGRESS", 0.75f, t2.plusSeconds(10));
        fixture.event(u2, pv1, f2, "HIDE", null, t2.plusSeconds(20));

        JsonNode body = ok(get("feed", at("2031-07-01T00:00:00Z"), at("2031-07-02T00:00:00Z")));

        assertRate(body.path("overall"), 8, 3);
        // Position 0: PV1 and PL, PL positive; 1: PI1 liked, PV1 hidden; 2: PV2 complete; 3, 4: nothing.
        JsonNode byPosition = body.path("byPosition");
        assertThat(byPosition.size()).isEqualTo(5); // position 60 is past the curve
        assertThat(byPosition.get(0).path("position").asInt()).isZero();
        assertRate(byPosition.get(0), 2, 1);
        assertRate(byPosition.get(1), 2, 1);
        assertRate(byPosition.get(2), 1, 1);
        assertRate(byPosition.get(3), 1, 0);
        assertRate(byPosition.get(4), 1, 0);

        JsonNode byConfig = body.path("byConfig");
        assertThat(byConfig.size()).isEqualTo(2);
        assertThat(byConfig.get(0).path("version").asInt()).isEqualTo(1);
        assertRate(byConfig.get(0), 5, 2);
        assertThat(byConfig.get(1).path("version").asInt()).isEqualTo(version);
        assertThat(byConfig.get(1).path("activatedAt").asText("")).isEqualTo("2031-06-30T09:00:00Z");
        assertRate(byConfig.get(1), 3, 1);

        assertRate(body.path("exploration"), 2, 1);
        assertRate(body.path("exploitation"), 6, 2);

        // Watched items only: 0.25 → [0.2, 0.3), 0.5 → [0.5, 0.6), 0.75 → [0.7, 0.8), 1.0 → [0.9, 1.0].
        JsonNode bins = body.path("watchHistogram");
        assertThat(bins.size()).isEqualTo(10);
        long[] counts = new long[10];
        for (int i = 0; i < 10; i++) {
            counts[i] = bins.get(i).path("count").asLong();
        }
        assertThat(counts).containsExactly(0, 0, 1, 0, 0, 1, 0, 1, 0, 1);
        assertThat(bins.get(9).path("to").asDouble()).isEqualTo(1.0);

        // By first-clip length (video first, watched): 15–30 s PV1 0.5, PV2 1.0; > 3 min PL 0.25, 0.75.
        JsonNode byLength = body.path("watchByClipLength");
        assertThat(byLength.size()).isEqualTo(6);
        assertThat(byLength.get(0).path("watched").asLong()).isZero();
        assertThat(byLength.get(0).path("meanWatchFraction").isNull()).isTrue();
        assertThat(byLength.get(1).path("label").asText("")).isEqualTo("15–30 s");
        assertThat(byLength.get(1).path("watched").asLong()).isEqualTo(2);
        assertThat(byLength.get(1).path("meanWatchFraction").asDouble()).isCloseTo(0.75, within(1e-6));
        assertThat(byLength.get(1).path("completionRate").asDouble()).isEqualTo(0.5);
        assertThat(byLength.get(5).path("watched").asLong()).isEqualTo(2);
        assertThat(byLength.get(5).path("meanWatchFraction").asDouble()).isCloseTo(0.5, within(1e-6));
        assertThat(byLength.get(5).path("completionRate").asDouble()).isZero();
        assertThat(byLength.get(5).path("toSeconds").isNull()).isTrue();

        // Photo first: PI1 liked, PI2 nothing. Video first: the other six, two positive.
        assertRate(body.path("photo"), 2, 1);
        assertRate(body.path("video"), 6, 2);
    }

    @Test
    void aPeriodOverNinetyDaysOrNotMovingForwardIsRefused() {
        Instant from = at("2031-01-01T00:00:00Z");
        for (String endpoint : List.of("overview", "feed", "content")) {
            assertRefused(get(endpoint, from, from.plus(Duration.ofDays(91))));
            assertRefused(get(endpoint, from, from));
            assertRefused(get(endpoint, from, from.minusSeconds(1)));
            assertThat(get(endpoint, from, from.plus(Duration.ofDays(90))).getStatusCode())
                    .as(endpoint + ": exactly 90 days is allowed")
                    .isEqualTo(HttpStatus.OK);
        }
    }

    private UUID video(UUID owner, int durationMs) {
        return fixture.media(owner, "VIDEO", "READY", at("2031-06-01T00:00:00Z"), 1_000L, durationMs);
    }

    private UUID image(UUID owner) {
        return fixture.media(owner, "IMAGE", "READY", at("2031-06-01T00:00:00Z"), 1_000L, null);
    }

    /** A published post with the clips in order. */
    private UUID post(UUID author, UUID... clips) {
        UUID post = fixture.post(author, "PUBLISHED", at("2031-06-01T00:00:00Z"), null);
        for (int i = 0; i < clips.length; i++) {
            fixture.clip(post, author, i, clips[i], null);
        }
        return post;
    }

    private ResponseEntity<JsonNode> get(String endpoint, Instant from, Instant to) {
        return api.get("/api/v1/admin/analytics/" + endpoint + "?from=" + from + "&to=" + to, api.admin());
    }

    private static void assertRefused(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("code").asText("")).isEqualTo("ANALYTICS_RANGE_INVALID");
    }

    private static void assertKpi(JsonNode kpi, double value, double previous) {
        assertThat(kpi.path("value").asDouble()).as("value").isCloseTo(value, within(1e-9));
        assertThat(kpi.path("previous").asDouble()).as("previous").isCloseTo(previous, within(1e-9));
    }

    private static void assertRate(JsonNode rate, long served, long positive) {
        assertThat(rate.path("served").asLong()).as("served").isEqualTo(served);
        assertThat(rate.path("positive").asLong()).as("positive").isEqualTo(positive);
        assertThat(rate.path("rate").asDouble()).as("rate").isCloseTo((double) positive / served, within(1e-9));
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static Instant at(String instant) {
        return Instant.parse(instant);
    }
}
