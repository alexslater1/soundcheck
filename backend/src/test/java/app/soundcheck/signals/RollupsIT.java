package app.soundcheck.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.signals.EngagementPriors.Prior;
import app.soundcheck.signals.internal.AffinityRollup;
import app.soundcheck.signals.internal.GlobalPriorRefresh;
import app.soundcheck.signals.internal.PostStatsRollup;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.MutableClock;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Events become the expected post counters, exactly once however often the rollup
 * runs; engagement becomes affinities that decay by half in 14 days; the global prior is computed from
 * the counters. (The scheduled jobs run the same code in the background during this test; every
 * assertion here holds whichever of the two folded an event in.)
 */
class RollupsIT extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    CatalogIngest catalogIngest;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PostStatsRollup postStats;

    @Autowired
    AffinityRollup affinityRollup;

    @Autowired
    GlobalPriorRefresh priorRefresh;

    @Autowired
    Affinities affinities;

    @Autowired
    EngagementPriors priors;

    @Autowired
    MutableClock clock;

    TestApi api;
    TestData data;
    TestApi.User author;
    TestApi.User viewer;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        author = api.register("statsauthor");
        viewer = api.register("statsviewer");
    }

    @Test
    void eventsBecomeThePostsCountersAndRunningTheRollupAgainCountsNothingTwice() {
        UUID post = post(author, null);
        UUID gone = UUID.randomUUID(); // no such post: its events are read and dropped
        send(
                viewer,
                event("IMPRESSION", post),
                event("IMPRESSION", post),
                event("IMPRESSION", post),
                event("VIEW_START", post),
                event("VIEW_START", post),
                progress(post, 0.25),
                progress(post, 0.5),
                progress(post, 0.75),
                event("COMPLETE", post),
                event("LIKE", post),
                event("COMMENT", post),
                event("SHARE", post),
                event("HIDE", post),
                event("PROFILE_TAP", post),
                event("IMPRESSION", gone),
                event("LIKE", gone));

        rollUpPostStats();

        Record stats = stats(post);
        assertThat(stats.get("impressions", Long.class)).isEqualTo(3);
        assertThat(stats.get("views", Long.class)).isEqualTo(2);
        assertThat(stats.get("completes", Long.class)).isEqualTo(1);
        assertThat(stats.get("likes", Long.class)).isEqualTo(1);
        assertThat(stats.get("comments", Long.class)).isEqualTo(1);
        assertThat(stats.get("shares", Long.class)).isEqualTo(1);
        assertThat(stats.get("hides", Long.class)).isEqualTo(1);
        // Three progress marks and the completion: one whole clip watched.
        assertThat(stats.get("watch_fraction_sum", Double.class)).isEqualTo(1.0);
        Record hour = dsl.fetchSingle(
                "select sum(impressions) as impressions, sum(engagements) as engagements"
                        + " from signals.post_stats_hourly where post_id = ?",
                post);
        assertThat(hour.get("impressions", Long.class)).isEqualTo(3);
        assertThat(hour.get("engagements", Long.class)).isEqualTo(4); // like + comment + share + complete
        assertThat(dsl.fetchCount(dsl.selectFrom("signals.post_stats").where("post_id = ?", gone)))
                .isZero();

        // Again, with nothing new: the same numbers.
        rollUpPostStats();
        rollUpPostStats();
        assertThat(stats(post).get("impressions", Long.class)).isEqualTo(3);
        assertThat(stats(post).get("likes", Long.class)).isEqualTo(1);

        // New events are added to what is there; more unlikes than likes never go below zero.
        send(viewer, event("IMPRESSION", post), event("UNLIKE", post), event("UNLIKE", post));
        rollUpPostStats();
        assertThat(stats(post).get("impressions", Long.class)).isEqualTo(4);
        assertThat(stats(post).get("likes", Long.class)).isZero();
        assertThat(stats(post).get("comments", Long.class)).isEqualTo(1);
    }

    @Test
    void engagementBecomesAffinityForTheAuthorTheArtistsAndTheirGenres() {
        UUID artist = data.artist("Affinity Act " + UUID.randomUUID());
        short genre = dsl.fetchSingle("select id from catalog.genre order by id limit 1")
                .get(0, Short.class);
        dsl.execute(
                "insert into catalog.artist_genre (artist_id, genre_id, weight) values (?, ?, 0.8)"
                        + " on conflict (artist_id, genre_id) do update set weight = 0.8",
                artist,
                genre);
        dsl.execute("delete from catalog.artist_genre where artist_id = ? and genre_id <> ?", artist, genre);
        UUID gig = data.gig(
                "Affinity night",
                data.venue("Soup " + UUID.randomUUID()),
                Instant.parse("2026-11-05T19:30:00Z"),
                artist);
        UUID post = post(author, gig);
        TestApi.User fickle = api.register("fickle");

        send(viewer, event("LIKE", post), event("COMPLETE", post)); // 0.10 + 0.05
        send(fickle, event("LIKE", post), event("UNLIKE", post)); // cancels out
        send(author, event("LIKE", post)); // one's own post says nothing about the author
        rollUpAffinities();

        assertThat(affinities.artists(viewer.id(), 10)).containsOnlyKeys(artist);
        assertThat(affinities.artists(viewer.id(), 10).get(artist)).isCloseTo(0.15, within(1e-3));
        assertThat(affinities.authors(viewer.id(), 10).get(author.id())).isCloseTo(0.15, within(1e-3));
        assertThat(affinities.genres(viewer.id()).get(genre)).isCloseTo(0.15 * 0.8, within(1e-3));
        assertThat(affinities.artists(fickle.id(), 10)).isEmpty();
        assertThat(affinities.authors(author.id(), 10)).isEmpty();
        assertThat(affinities.artists(author.id(), 10).get(artist)).isCloseTo(0.10, within(1e-3));

        // Running it again adds nothing.
        rollUpAffinities();
        assertThat(affinities.artists(viewer.id(), 10).get(artist)).isCloseTo(0.15, within(1e-3));
    }

    @Test
    void anAffinityDecaysByHalfOverFourteenDays() {
        UUID artist = data.artist("Fading Act " + UUID.randomUUID());
        UUID gig = data.gig(
                "Fading night",
                data.venue("Night & Day " + UUID.randomUUID()),
                Instant.parse("2026-11-06T19:30:00Z"),
                artist);
        UUID post = post(author, gig);
        // A score of 0.4 written fourteen days ago.
        dsl.execute(
                "insert into signals.user_artist_affinity (user_id, artist_id, score, updated_at)"
                        + " values (?, ?, 0.4, ?::timestamptz - interval '14 days')",
                viewer.id(),
                artist,
                Instant.now(clock).toString());

        // Read today, it is worth half.
        assertThat(affinities.artists(viewer.id(), 10).get(artist)).isCloseTo(0.2, within(1e-3));

        // New engagement is added to the decayed score: 0.4 · 0.5 + 0.15.
        send(viewer, event("SHARE", post));
        rollUpAffinities();
        assertThat(affinities.artists(viewer.id(), 10).get(artist)).isCloseTo(0.35, within(1e-3));
        assertThat(dsl.fetchSingle(
                                "select score from signals.user_artist_affinity where user_id = ? and artist_id = ?",
                                viewer.id(),
                                artist)
                        .get(0, Double.class))
                .isCloseTo(0.35, within(1e-3));
    }

    @Test
    void theGlobalPriorIsTheOverallRateAndTheNinetyNinthPercentileOfTheSmoothedRates() {
        UUID first = post(author, null);
        UUID second = post(author, null);
        // Computed over exactly two posts, in a transaction that is rolled back afterwards.
        tx.executeWithoutResult(status -> {
            dsl.execute("delete from signals.post_stats");
            dsl.execute(
                    "insert into signals.post_stats (post_id, impressions, likes, comments) values (?, 100, 10, 5)",
                    first);
            dsl.execute("insert into signals.post_stats (post_id, impressions, likes) values (?, 300, 20)", second);

            Prior prior = priorRefresh.refresh();

            // m = (10 + 2·5 + 20) / 400; smoothed rates (20 + 20m)/120 and (20 + 20m)/320.
            assertThat(prior.meanRate()).isCloseTo(0.1, within(1e-9));
            double high = 22.0 / 120;
            double low = 22.0 / 320;
            assertThat(prior.p99Rate()).isCloseTo(low + 0.99 * (high - low), within(1e-9));
            assertThat(priors.current()).isEqualTo(prior);
            status.setRollbackOnly();
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private void rollUpPostStats() {
        while (postStats.rollUpBatch(Instant.now(clock)) > 0) {
            // until nothing is left
        }
    }

    private void rollUpAffinities() {
        Instant now = Instant.now(clock);
        while (affinityRollup.rollUpBatch(now, now)) {
            // until nothing is left
        }
    }

    private UUID post(TestApi.User user, UUID gig) {
        Map<String, Object> body = new HashMap<>();
        body.put("mediaId", data.video(user.id(), "READY"));
        if (gig != null) {
            body.put("gigId", gig);
        }
        return UUID.fromString(
                api.post("/api/v1/posts", body, user).getBody().path("id").asText(""));
    }

    @SafeVarargs
    private void send(TestApi.User user, Map<String, Object>... batch) {
        assertThat(api.post("/api/v1/events", Map.of("events", List.of(batch)), user)
                        .getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    private static Map<String, Object> event(String type, UUID post) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.put("postId", post);
        event.put("surface", "FOR_YOU");
        return event;
    }

    private static Map<String, Object> progress(UUID post, double fraction) {
        Map<String, Object> event = event("WATCH_PROGRESS", post);
        event.put("value", fraction);
        return event;
    }

    private Record stats(UUID post) {
        return dsl.fetchSingle("select * from signals.post_stats where post_id = ?", post);
    }
}
