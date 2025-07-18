package app.soundcheck.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.feed.internal.CandidateGenerator;
import app.soundcheck.feed.internal.CandidateGenerator.Featured;
import app.soundcheck.feed.internal.CandidateRepository.Candidate;
import app.soundcheck.feed.internal.UserContext;
import app.soundcheck.feed.internal.UserContextLoader;
import app.soundcheck.feed.internal.ranking.CandidateSource;
import app.soundcheck.feed.internal.ranking.RankingConfig;
import app.soundcheck.signals.EngagementPriors.Prior;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.MutableClock;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * On a small hand-built dataset: which posts come from which candidate sources (and
 * which never do), what the user context holds, and the exact feature values of three chosen posts.
 *
 * <p>The database is shared with the other tests, so the candidate list also holds their posts; every
 * assertion is about the posts made here.
 */
class CandidateGenerationIT extends IntegrationTest {

    static final short INDIE = 2;
    static final short JAZZ = 14;
    static final Prior PRIOR = new Prior(0.1, 0.5);
    static final RankingConfig.Params PARAMS = new RankingConfig.Params(
            24,
            20,
            0.8,
            0.1,
            200,
            10,
            Map.of(
                    "FOLLOWING",
                    300,
                    "ARTIST",
                    300,
                    "GIG",
                    200,
                    "TASTE",
                    300,
                    "LOCAL",
                    200,
                    "TRENDING",
                    200,
                    "FRESH",
                    100),
            50,
            3);

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
    UserContextLoader contexts;

    @Autowired
    CandidateGenerator generator;

    @Autowired
    MutableClock clock;

    TestApi api;
    TestData data;

    @Test
    void eachGeneratorFindsItsPostsTheFiltersHoldAndTheFeaturesAreExact() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);

        // ── the viewer: lives in Manchester, likes indie ────────────────────────────────────────
        TestApi.User viewer = api.register("viewer");
        JsonNode city = api.get("/api/v1/cities?q=manchester", null).getBody().path(0);
        double lat = city.path("lat").asDouble();
        double lng = city.path("lng").asDouble();
        ok(api.patch("/api/v1/me", Map.of("homeCityId", city.path("id").asInt()), viewer));
        ok(api.put("/api/v1/me/genres", Map.of("genres", List.of(Map.of("genreId", INDIE, "weight", 1.0))), viewer));

        // ── the people ──────────────────────────────────────────────────────────────────────────
        TestApi.User followed = api.register("followed");
        TestApi.User friend = api.register("friend");
        TestApi.User stranger = api.register("stranger");
        TestApi.User blocked = api.register("blocked");
        TestApi.User leaver = api.register("leaver");
        api.put("/api/v1/users/" + followed.id() + "/follow", null, viewer);
        api.put("/api/v1/users/" + friend.id() + "/follow", null, viewer);

        // ── artists, venues, gigs ───────────────────────────────────────────────────────────────
        UUID followedArtist = artist("Followed Act", Map.of(INDIE, 0.8, JAZZ, 0.2));
        UUID indieArtist = artist("Indie Act", Map.of(INDIE, 1.0));
        UUID jazzArtist = artist("Jazz Act", Map.of(JAZZ, 1.0));
        api.put("/api/v1/artists/" + followedArtist + "/follow", null, viewer);
        UUID nearGig = data.gig(
                "Round the corner",
                data.venue("At the centroid " + UUID.randomUUID(), lat, lng),
                Instant.parse("2026-09-20T19:30:00Z"),
                jazzArtist);
        UUID farGig = data.gig(
                "Far away",
                data.venue("Two degrees north " + UUID.randomUUID(), lat + 2.0, lng),
                Instant.parse("2026-09-21T19:30:00Z"),
                jazzArtist);
        ok(api.put("/api/v1/gigs/" + nearGig + "/attendance", Map.of("status", "WENT"), viewer));

        // ── the posts ───────────────────────────────────────────────────────────────────────────
        UUID pFollow = post(followed, null, List.of(), now.minus(Duration.ofHours(12)));
        stats(pFollow, 100, 10, 5, 0, 5);
        api.put("/api/v1/posts/" + pFollow + "/like", null, friend);

        UUID pFollowNewest = post(followed, null, List.of(), now.minus(Duration.ofHours(4)));
        stats(pFollowNewest, 60, 0, 0, 0, 0);
        // Fifteen days old: outside the 14-day window of FOLLOWING.
        UUID pFollowOld = post(followed, null, List.of(), now.minus(Duration.ofDays(15)));

        UUID pArtist = post(stranger, null, List.of(followedArtist), now.minus(Duration.ofDays(5)));
        UUID pGig = post(stranger, nearGig, List.of(), now.minus(Duration.ofDays(20)));
        UUID pLocal = post(stranger, nearGig, List.of(), now.minus(Duration.ofDays(2)));
        stats(pLocal, 60, 0, 0, 0, 0);
        hourly(pLocal, now.minus(Duration.ofHours(3)), 5);
        UUID pFar = post(stranger, farGig, List.of(), now.minus(Duration.ofDays(2)));
        stats(pFar, 60, 0, 0, 0, 0);
        UUID pTrending = post(stranger, null, List.of(), now.minus(Duration.ofDays(10)));
        stats(pTrending, 1_000, 0, 0, 0, 0);
        hourly(pTrending, now.minus(Duration.ofHours(3)), 500);
        UUID pFresh = post(stranger, null, List.of(), now);
        UUID pTaste = post(stranger, null, List.of(indieArtist), now.minus(Duration.ofDays(1)));
        stats(pTaste, 60, 0, 0, 0, 0);
        UUID pJazz = post(stranger, null, List.of(jazzArtist), now.minus(Duration.ofDays(4)));

        // Never candidates: the viewer's own, a blocked author's, a hidden one, one still processing,
        // one whose author deleted their account.
        UUID pOwn = post(viewer, null, List.of(), now);
        UUID pBlocked = post(blocked, null, List.of(), now);
        api.put("/api/v1/users/" + blocked.id() + "/block", null, viewer);
        UUID pHidden = post(followed, null, List.of(), now);
        assertThat(api.post("/api/v1/posts/" + pHidden + "/hide", null, viewer).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        UUID pPending = UUID.fromString(api.post(
                        "/api/v1/posts",
                        Map.of("clips", List.of(Map.of("mediaId", data.video(followed.id(), "UPLOADED")))),
                        followed)
                .getBody()
                .path("id")
                .asText(""));
        UUID pDeleted = post(leaver, null, List.of(), now);
        assertThat(api.call(HttpMethod.DELETE, "/api/v1/me", Map.of("password", "correct horse battery"), leaver)
                        .getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        // What the viewer has seen, and what their engagement has taught the system.
        api.post(
                "/api/v1/events",
                Map.of("events", List.of(Map.of("type", "IMPRESSION", "postId", pTaste, "surface", "FOR_YOU"))),
                viewer);
        dsl.execute(
                "insert into signals.user_artist_affinity (user_id, artist_id, score, updated_at) values (?, ?, 0.7, ?::timestamptz)",
                viewer.id(),
                followedArtist,
                now.toString());
        dsl.execute(
                "insert into signals.user_author_affinity (user_id, author_id, score, updated_at) values (?, ?, 0.3, ?::timestamptz)",
                viewer.id(),
                stranger.id(),
                now.toString());

        // ── the user context ────────────────────────────────────────────────────────────────────
        UserContext context = contexts.load(viewer.id(), PARAMS.seenWindowDays(), now);

        assertThat(context.followedUsers()).containsExactlyInAnyOrder(followed.id(), friend.id());
        assertThat(context.followedArtists()).containsExactly(followedArtist);
        assertThat(context.attendance()).containsExactly(Map.entry(nearGig, "WENT"));
        assertThat(context.genreVector()).containsOnlyKeys(INDIE);
        assertThat(context.genreVector().get(INDIE)).isCloseTo(1.0, within(1e-9));
        assertThat(context.topGenres()).containsExactly(INDIE);
        assertThat(context.home().lat()).isCloseTo(lat, within(1e-6));
        assertThat(context.blocked()).containsExactly(blocked.id());
        assertThat(context.hidden()).containsExactly(pHidden);
        assertThat(context.seen()).containsExactly(pTaste);
        assertThat(context.artistAffinity().get(followedArtist)).isCloseTo(0.7, within(1e-3));
        assertThat(context.authorAffinity().get(stranger.id())).isCloseTo(0.3, within(1e-3));

        // ── the candidates and their sources ────────────────────────────────────────────────────
        List<Candidate> candidates = generator.candidates(context, PARAMS, now);
        Map<UUID, Set<CandidateSource>> sources =
                candidates.stream().collect(Collectors.toMap(Candidate::postId, Candidate::sources));

        assertThat(sources.get(pFollow)).containsExactly(CandidateSource.FOLLOWING);
        assertThat(sources.get(pFollowNewest)).containsExactly(CandidateSource.FOLLOWING);
        assertThat(sources).doesNotContainKey(pFollowOld);
        assertThat(sources.get(pArtist)).containsExactlyInAnyOrder(CandidateSource.ARTIST, CandidateSource.TASTE);
        assertThat(sources.get(pGig)).containsExactly(CandidateSource.GIG); // any age, but too old for LOCAL
        // Near home and recent, from a gig the viewer went to; its five engagements in the last 48 hours
        // also put it in the trending pool.
        assertThat(sources.get(pLocal))
                .containsExactlyInAnyOrder(CandidateSource.GIG, CandidateSource.LOCAL, CandidateSource.TRENDING);
        assertThat(sources.get(pTrending)).containsExactly(CandidateSource.TRENDING);
        assertThat(sources.get(pFresh)).containsExactly(CandidateSource.FRESH);
        assertThat(sources.get(pTaste)).containsExactly(CandidateSource.TASTE);
        // 220 km away, a genre the viewer did not choose, and the five that must never be served.
        assertThat(sources).doesNotContainKeys(pFar, pJazz, pOwn, pBlocked, pHidden, pPending, pDeleted);
        assertThat(candidates).extracting(Candidate::postId).doesNotHaveDuplicates();
        assertThat(candidates)
                .noneMatch(c -> c.authorId().equals(viewer.id()) || c.authorId().equals(blocked.id()));

        // With a cap of zero a generator is switched off.
        Map<String, Integer> noFresh = new HashMap<>(PARAMS.candidateCaps());
        noFresh.put("FRESH", 0);
        RankingConfig.Params withoutFresh = new RankingConfig.Params(24, 20, 0.8, 0.1, 200, 10, noFresh, 50, 3);
        assertThat(generator.candidates(context, withoutFresh, now))
                .extracting(Candidate::postId)
                .contains(pFollow)
                .doesNotContain(pFresh);

        // A cap keeps the first of the generator's own order: the newest followed post, the most engaged
        // trending one.
        Map<String, Integer> one = new HashMap<>(PARAMS.candidateCaps());
        one.put("FOLLOWING", 1);
        one.put("TRENDING", 1);
        one.put("FRESH", 0);
        one.put("GIG", 0);
        one.put("LOCAL", 0);
        Map<UUID, Set<CandidateSource>> capped =
                generator
                        .candidates(context, new RankingConfig.Params(24, 20, 0.8, 0.1, 200, 10, one, 50, 3), now)
                        .stream()
                        .collect(Collectors.toMap(Candidate::postId, Candidate::sources));
        assertThat(capped.get(pFollowNewest)).containsExactly(CandidateSource.FOLLOWING);
        assertThat(capped).doesNotContainKey(pFollow); // twelve hours old: behind the newest one
        assertThat(capped.get(pTrending)).containsExactly(CandidateSource.TRENDING);
        assertThat(capped).doesNotContainKey(pLocal); // 5 engagements against 500

        // ── the features of three posts, exactly ────────────────────────────────────────────────
        Map<UUID, Featured> featured = generator.features(context, candidates, PARAMS, PRIOR, now).stream()
                .collect(Collectors.toMap(f -> f.candidate().postId(), Function.identity()));

        // A followed author's post from 12 hours ago, liked by one friend, with known counters.
        assertThat(featured.get(pFollow).features().toArray())
                .containsExactly(
                        new double[] {
                            1.0, // follows_author
                            0.0, // follows_artist
                            0.0, // artist_affinity
                            0.5, // author_affinity: the follow floor
                            0.0, // genre_similarity: no tags
                            0.0, // gig_relation
                            0.0, // proximity
                            Math.pow(2, -0.5), // recency: 12 h of a 24 h half-life
                            0.45, // engagement_quality: (10 + 2·5 + 0 + 5 + 20·0.1) / 120 / 0.5
                            1 - Math.exp(-0.5), // social_proof: one friend
                            0.0, // seen
                            0.0 // position_prior
                        },
                        within(1e-6));
        // A stranger's post tagged with a followed artist, five days old, never shown.
        assertThat(featured.get(pArtist).features().toArray())
                .containsExactly(
                        new double[] {
                            0.0,
                            1.0, // follows_artist
                            0.7, // artist_affinity: 0.7 learned, above the follow floor
                            0.3, // author_affinity learned from engagement
                            0.8 / Math.sqrt(0.68), // genre_similarity: indie against (indie 0.8, jazz 0.2)
                            0.0,
                            0.0,
                            Math.pow(2, -5), // recency: five half-lives
                            0.2, // engagement_quality: the global mean over the p99
                            0.0,
                            0.0,
                            0.0
                        },
                        within(1e-3));
        // A post from a gig the viewer went to, at a venue on their home city's centroid, 20 days old.
        assertThat(featured.get(pGig).features().toArray())
                .containsExactly(
                        new double[] {
                            0.0,
                            0.0,
                            0.0,
                            0.3,
                            0.0,
                            1.0, // gig_relation: WENT
                            1.0, // proximity: no distance at all
                            Math.pow(2, -20), // recency
                            0.2,
                            0.0,
                            0.0,
                            0.0
                        },
                        within(1e-3));
        // And single values on others: seen, and a venue two degrees of latitude (about 222 km) away.
        assertThat(featured.get(pTaste).features().toArray()[10]).isEqualTo(1.0);
        assertThat(featured.get(pTaste).features().toArray()[4]).isCloseTo(1.0, within(1e-9));
        assertThat(featured.get(pLocal).facts().distanceKm()).isCloseTo(0.0, within(0.01));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private UUID artist(String name, Map<Short, Double> genres) {
        UUID artist = data.artist(name + " " + UUID.randomUUID());
        dsl.execute("delete from catalog.artist_genre where artist_id = ?", artist);
        genres.forEach((genre, weight) -> dsl.execute(
                "insert into catalog.artist_genre (artist_id, genre_id, weight) values (?, ?, ?)",
                artist,
                genre,
                weight));
        return artist;
    }

    /** A published post (READY clip) with the given tags, published at the given moment. */
    private UUID post(TestApi.User author, UUID gig, List<UUID> artists, Instant publishedAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("clips", List.of(Map.of("mediaId", data.video(author.id(), "READY"))));
        body.put("artistIds", artists);
        if (gig != null) {
            body.put("gigId", gig);
        }
        UUID id = UUID.fromString(
                api.post("/api/v1/posts", body, author).getBody().path("id").asText(""));
        dsl.execute("update content.post set published_at = ?::timestamptz where id = ?", publishedAt.toString(), id);
        return id;
    }

    private void stats(UUID post, long impressions, long likes, long comments, long shares, long completes) {
        dsl.execute(
                "insert into signals.post_stats (post_id, impressions, likes, comments, shares, completes)"
                        + " values (?, ?, ?, ?, ?, ?)",
                post,
                impressions,
                likes,
                comments,
                shares,
                completes);
    }

    private void hourly(UUID post, Instant when, int engagements) {
        dsl.execute(
                "insert into signals.post_stats_hourly (post_id, hour, impressions, engagements)"
                        + " values (?, date_trunc('hour', ?::timestamptz), 0, ?)",
                post,
                when.toString(),
                engagements);
    }

    private static void ok(org.springframework.http.ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
    }
}
