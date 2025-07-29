package app.soundcheck.content;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.Parallel;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rules that were untested or wrong: pages are not left short by posts the
 * viewer cannot see, a deleted account's posts are closed during the grace period, an explicit empty
 * artist list, retries after deletion, the admin override, two requests for one clip, text PostgreSQL
 * cannot store.
 */
class PostRulesIT extends IntegrationTest {

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

    TestApi api;
    TestData data;
    TestApi.User author;
    TestApi.User viewer;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        author = api.register("writer");
        viewer = api.register("reader");
    }

    @Test
    void aPageIsFilledPastPostsOfPeopleTheViewerCannotSee() {
        TestApi.User blocker = api.register("blocker");
        TestApi.User leaver = api.register("leaver");
        UUID gig = data.gig(
                "Shared night",
                data.venue("Gorilla " + UUID.randomUUID()),
                Instant.parse("2026-08-03T19:30:00Z"),
                data.artist("Act " + UUID.randomUUID()));
        api.put("/api/v1/users/" + author.id() + "/follow", null, viewer);
        api.put("/api/v1/users/" + leaver.id() + "/follow", null, viewer);

        // Oldest first: two posts the viewer may see, then newer ones by a blocker and by a leaver. One
        // fan's clips from a gig and day are one post, so each post is from another day of it.
        LocalDate first = LocalDate.of(2026, 8, 3);
        List<String> visible = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            visible.add(0, post(author, gig, first.plusDays(i)));
        }
        for (int i = 0; i < 3; i++) {
            post(blocker, gig, first.plusDays(i));
            post(leaver, gig, first.plusDays(i));
        }
        // The gig counts its published clips (GigDetail.postsCount).
        assertThat(api.get("/api/v1/gigs/" + gig, viewer)
                        .getBody()
                        .path("postsCount")
                        .asInt())
                .isEqualTo(8);
        api.put("/api/v1/users/" + viewer.id() + "/block", null, blocker);
        assertThat(api.call(HttpMethod.DELETE, "/api/v1/me", Map.of("password", "correct horse battery"), leaver)
                        .getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        // The six newest rows are all invisible; the page still holds the two visible posts.
        JsonNode gigPage =
                api.get("/api/v1/gigs/" + gig + "/posts?limit=2", viewer).getBody();
        assertThat(ids(gigPage.path("items"))).containsExactlyElementsOf(visible);
        assertThat(gigPage.path("nextCursor").isNull()).isTrue();

        // The follow of the leaver is still there (until the purge); the feed is not left empty by it.
        JsonNode feed = api.get("/api/v1/feed/following?limit=2", viewer).getBody();
        assertThat(ids(feed.path("items"))).containsExactlyElementsOf(visible);
    }

    @Test
    void aDeletedAccountsPostIsClosedToEveryoneBeforeThePurge() {
        String postId = post(author, null);
        assertThat(api.put("/api/v1/posts/" + postId + "/like", null, viewer).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.post("/api/v1/posts/" + postId + "/comments", Map.of("body", "before"), viewer)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(api.call(HttpMethod.DELETE, "/api/v1/me", Map.of("password", "correct horse battery"), author)
                        .getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        TestApi.User other = api.register("other");

        assertThat(api.get("/api/v1/posts/" + postId, viewer).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/api/v1/posts/" + postId + "/comments", viewer).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.put("/api/v1/posts/" + postId + "/like", null, other).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.post("/api/v1/posts/" + postId + "/comments", Map.of("body", "after"), other)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.post("/api/v1/posts/" + postId + "/hide", null, other).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.post("/api/v1/posts/" + postId + "/report", Map.of("reason", "SPAM"), other)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(counters(postId)).containsExactly(1, 1);
        // A like given before can still be taken back.
        assertThat(api.delete("/api/v1/posts/" + postId + "/like", viewer).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(counters(postId)).containsExactly(0, 1);
    }

    @Test
    void aLikeCanBeTakenBackAfterBeingBlocked() {
        String postId = post(author, null);
        api.put("/api/v1/posts/" + postId + "/like", null, viewer);
        api.put("/api/v1/users/" + viewer.id() + "/block", null, author);

        assertThat(api.put("/api/v1/posts/" + postId + "/like", null, viewer).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<JsonNode> unliked = api.delete("/api/v1/posts/" + postId + "/like", viewer);
        assertThat(unliked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(counters(postId)).containsExactly(0, 0);
    }

    @Test
    void anEmptyArtistListTagsNobodyAndALeftOutOneTagsTheClipsActs() {
        UUID headliner = data.artist("Top " + UUID.randomUUID());
        UUID gig = data.gig(
                "Bill night", data.venue("YES " + UUID.randomUUID()), Instant.parse("2026-08-04T19:30:00Z"), headliner);

        JsonNode none = created(api.post(
                "/api/v1/posts",
                Map.of(
                        "clips",
                        List.of(Map.of("mediaId", data.video(author.id(), "READY"))),
                        "gigId",
                        gig,
                        "artistIds",
                        List.of()),
                author));
        assertThat(none.path("artists").size()).isZero();
        assertThat(ids(api.get("/api/v1/artists/" + headliner + "/posts", viewer)
                        .getBody()
                        .path("items")))
                .isEmpty();

        // Left out: the clips' acts, no longer the gig's whole bill. Two acts and a clip of
        // neither: no act (on a single-act gig the clip would take that act).
        UUID other = data.gig(
                "Other night",
                data.venue("YES " + UUID.randomUUID()),
                Instant.parse("2026-08-05T19:30:00Z"),
                headliner,
                data.artist("Support " + UUID.randomUUID()));
        JsonNode noAct = created(api.post(
                "/api/v1/posts",
                Map.of("clips", List.of(Map.of("mediaId", data.video(author.id(), "READY"))), "gigId", other),
                author));
        assertThat(noAct.path("artists").size()).isZero();
        UUID third = data.gig(
                "Third night",
                data.venue("YES " + UUID.randomUUID()),
                Instant.parse("2026-08-06T19:30:00Z"),
                headliner);
        JsonNode withAct = created(api.post(
                "/api/v1/posts",
                Map.of(
                        "clips",
                        List.of(Map.of("mediaId", data.video(author.id(), "READY"), "artistId", headliner)),
                        "gigId",
                        third),
                author));
        assertThat(ids(withAct.path("artists"))).containsExactly(headliner.toString());
    }

    @Test
    void aRetryAfterTheDeletionOfWhatItCreatedFindsNothing() {
        String postKey = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("clips", List.of(Map.of("mediaId", data.video(author.id(), "READY"))));
        String postId = created(withKey("/api/v1/posts", body, postKey, author))
                .path("id")
                .asText("");
        String commentKey = UUID.randomUUID().toString();
        String commentPath = "/api/v1/posts/" + postId + "/comments";
        String commentId = created(withKey(commentPath, Map.of("body", "hello"), commentKey, viewer))
                .path("id")
                .asText("");

        assertThat(api.delete("/api/v1/comments/" + commentId, viewer).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(withKey(commentPath, Map.of("body", "hello"), commentKey, viewer)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(api.delete("/api/v1/posts/" + postId, author).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(withKey("/api/v1/posts", body, postKey, author).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anAdminCanDeleteAnyPostAndCommentAndNobodyElseCan() {
        String postId = post(author, null);
        String commentId = created(api.post("/api/v1/posts/" + postId + "/comments", Map.of("body", "rude"), viewer))
                .path("id")
                .asText("");
        TestApi.User stranger = api.register("stranger");
        String token = api.post(
                        "/api/v1/auth/login",
                        Map.of("email", "admin@test.local", "password", "admin-test-passphrase"),
                        null)
                .getBody()
                .path("accessToken")
                .asText("");
        TestApi.User admin = new TestApi.User(UUID.randomUUID(), "admin", "admin@test.local", token);

        assertThat(api.delete("/api/v1/comments/" + commentId, stranger).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.delete("/api/v1/posts/" + postId, stranger).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(api.delete("/api/v1/comments/" + commentId, admin).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(counters(postId)).containsExactly(0, 0);
        assertThat(api.delete("/api/v1/posts/" + postId, admin).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.get("/api/v1/posts/" + postId, author).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void twoRequestsAtOnceForOneClipCreateOnePost() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID clip = data.video(author.id(), "READY");

            List<HttpStatus> statuses =
                    Parallel.run(2, i -> (HttpStatus)
                            api.post("/api/v1/posts", Map.of("clips", List.of(Map.of("mediaId", clip))), author)
                                    .getStatusCode());

            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
            assertThat(dsl.fetchCount(dsl.selectFrom("content.post_clip").where("media_id = ?", clip)))
                    .isEqualTo(1);
        }
    }

    @Test
    void textTheDatabaseCannotStoreIsABadRequest() {
        UUID clip = data.video(author.id(), "READY");

        ResponseEntity<JsonNode> refused = api.post(
                "/api/v1/posts",
                Map.of("clips", List.of(Map.of("mediaId", clip)), "caption", "nul \u0000 inside"),
                author);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().path("code").asText("")).isEqualTo("INVALID_TEXT");
        // Nothing was kept: the clip can still be posted.
        created(api.post(
                "/api/v1/posts", Map.of("clips", List.of(Map.of("mediaId", clip)), "caption", "fine"), author));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** A published post by the user (its clip is READY), optionally at a gig, tagging nobody. */
    private String post(TestApi.User user, UUID gig, LocalDate day) {
        return created(api.post(
                        "/api/v1/posts",
                        Map.of(
                                "clips",
                                List.of(Map.of("mediaId", data.video(user.id(), "READY"))),
                                "gigId",
                                gig,
                                "day",
                                day.toString(),
                                "artistIds",
                                List.of()),
                        user))
                .path("id")
                .asText("");
    }

    private String post(TestApi.User user, UUID gig) {
        Map<String, Object> body = gig == null
                ? Map.of("clips", List.of(Map.of("mediaId", data.video(user.id(), "READY"))))
                : Map.of(
                        "clips",
                        List.of(Map.of("mediaId", data.video(user.id(), "READY"))),
                        "gigId",
                        gig,
                        "artistIds",
                        List.of());
        return created(api.post("/api/v1/posts", body, user)).path("id").asText("");
    }

    private ResponseEntity<JsonNode> withKey(String path, Object body, String key, TestApi.User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(user.accessToken());
        headers.set("X-Client", "android");
        headers.set("Idempotency-Key", key);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    /** like_count and comment_count as stored. */
    private List<Integer> counters(String postId) {
        return dsl.fetchOne("select like_count, comment_count from content.post where id = ?", UUID.fromString(postId))
                .map(r -> List.of(r.get(0, Integer.class), r.get(1, Integer.class)));
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.path("id").asText("")));
        return ids;
    }

    private static JsonNode created(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
