package app.soundcheck.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.content.internal.PostService;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
 * A post is created for an uploaded clip, waits in PENDING_MEDIA, and is published by
 * the worker's {@code MediaReady} event (or failed by {@code MediaFailed}); lists show PUBLISHED posts
 * only; ownership, idempotency and the avatar flow.
 */
class PostApiIT extends IntegrationTest {

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
    PostService postService;

    TestApi api;
    TestData data;
    TestApi.User author;
    TestApi.User viewer;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        author = api.register("poster");
        viewer = api.register("viewer");
    }

    @Test
    void aPostWaitsForItsClipAndIsPublishedByTheWorkersEvent() {
        UUID venue = data.venue("The Deaf Institute " + UUID.randomUUID());
        UUID headliner = data.artist("Headliner " + UUID.randomUUID());
        UUID support = data.artist("Support " + UUID.randomUUID());
        UUID gig = data.gig("A night out", venue, Instant.parse("2026-08-01T19:30:00Z"), headliner, support);
        UUID clip = data.video(author.id(), "UPLOADED");

        // Posted straight after the upload: nothing to play yet.
        ResponseEntity<JsonNode> created = api.post(
                "/api/v1/posts",
                Map.of("clips", List.of(Map.of("mediaId", clip)), "caption", "  What a night  ", "gigId", gig),
                author);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode post = created.getBody();
        String postId = post.path("id").asText("");
        assertThat(post.path("status").asText("")).isEqualTo("PENDING_MEDIA");
        assertThat(post.path("publishedAt").isNull()).isTrue();
        assertThat(post.path("caption").asText("")).isEqualTo("What a night");
        assertThat(post.path("author").path("handle").asText("")).isEqualTo(author.handle());
        assertThat(post.path("gig").path("title").asText("")).isEqualTo("A night out");
        // No artists given: the gig's bill, headliner first.
        assertThat(ids(post.path("artists"))).containsExactly(headliner.toString(), support.toString());
        assertThat(post.path("playback").path("status").asText("")).isEqualTo("UPLOADED");
        assertThat(post.path("likeCount").asInt()).isZero();
        assertThat(post.path("likedByMe").asBoolean()).isFalse();

        // Only its author can see it; no list shows it.
        assertThat(api.get("/api/v1/posts/" + postId, author).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.get("/api/v1/posts/" + postId, viewer).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(items("/api/v1/users/" + author.id() + "/posts", viewer)).isEmpty();
        assertThat(items("/api/v1/gigs/" + gig + "/posts", viewer)).isEmpty();
        assertThat(ids(pageOf("/api/v1/me/posts", author))).containsExactly(postId);

        // The worker finishes: the listener publishes the post.
        data.workerFinishes(clip, author.id());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                        api.get("/api/v1/posts/" + postId, viewer).getStatusCode())
                .isEqualTo(HttpStatus.OK));
        JsonNode published = api.get("/api/v1/posts/" + postId, viewer).getBody();
        assertThat(published.path("status").asText("")).isEqualTo("PUBLISHED");
        assertThat(published.path("publishedAt").isNull()).isFalse();
        assertThat(published.path("playback").path("status").asText("")).isEqualTo("READY");
        assertThat(published.path("playback").path("hlsUrl").asText("")).endsWith("/media/" + clip + "/v1/master.m3u8");

        // Now every list that should show it does.
        for (String list : List.of(
                "/api/v1/users/" + author.id() + "/posts",
                "/api/v1/gigs/" + gig + "/posts",
                "/api/v1/artists/" + headliner + "/posts",
                "/api/v1/artists/" + support + "/posts",
                "/api/v1/venues/" + venue + "/posts")) {
            assertThat(ids(pageOf(list, viewer))).as(list).containsExactly(postId);
        }
        // A second MediaReady (at-least-once delivery) changes nothing.
        String publishedAt = published.path("publishedAt").asText("");
        data.workerFinishes(clip, author.id());
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(
                        api.get("/api/v1/posts/" + postId, viewer)
                                .getBody()
                                .path("publishedAt")
                                .asText(""))
                .isEqualTo(publishedAt));
    }

    @Test
    void aClipThatFailsFailsItsPostAndAReadyClipIsPublishedAtOnce() {
        UUID failing = data.video(author.id(), "UPLOADED");
        String failed = created(api.post("/api/v1/posts", Map.of("clips", List.of(Map.of("mediaId", failing))), author))
                .path("id")
                .asText("");

        data.workerGivesUp(failing, author.id(), true);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(api.get("/api/v1/posts/" + failed, author)
                        .getBody()
                        .path("status")
                        .asText(""))
                .isEqualTo("FAILED"));
        assertThat(api.get("/api/v1/posts/" + failed, viewer).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(items("/api/v1/users/" + author.id() + "/posts", viewer)).isEmpty();
        // The author still sees it on their own grid, as failed.
        assertThat(pageOf("/api/v1/me/posts", author).get(0).path("status").asText(""))
                .isEqualTo("FAILED");

        // Posting a clip that is already READY publishes immediately; no gig and no artists is fine.
        UUID ready = data.video(author.id(), "READY");
        JsonNode post = created(api.post("/api/v1/posts", Map.of("clips", List.of(Map.of("mediaId", ready))), author));
        assertThat(post.path("status").asText("")).isEqualTo("PUBLISHED");
        assertThat(post.path("publishedAt").isNull()).isFalse();
        assertThat(post.path("gig").isNull()).isTrue();
        assertThat(post.path("artists").size()).isZero();
        assertThat(post.path("caption").isNull()).isTrue();
    }

    @Test
    void aPostWhoseEventWasMissedIsCaughtUpByTheReconciler() {
        UUID clip = data.video(author.id(), "UPLOADED");
        String postId = created(api.post("/api/v1/posts", Map.of("clips", List.of(Map.of("mediaId", clip))), author))
                .path("id")
                .asText("");
        // The clip became READY but no event reached the listener (the process died in between).
        data.setStatus(clip, "READY");
        assertThat(api.get("/api/v1/posts/" + postId, author)
                        .getBody()
                        .path("status")
                        .asText(""))
                .isEqualTo("PENDING_MEDIA");

        // Too young to be touched: the listener normally gets there first.
        assertThat(postService.reconcilePending(Instant.now().minusSeconds(30), 100))
                .isZero();
        assertThat(postService.reconcilePending(Instant.now().plusSeconds(1), 100))
                .isEqualTo(1);

        assertThat(api.get("/api/v1/posts/" + postId, viewer)
                        .getBody()
                        .path("status")
                        .asText(""))
                .isEqualTo("PUBLISHED");
        assertThat(postService.reconcilePending(Instant.now().plusSeconds(1), 100))
                .isZero();
    }

    @Test
    void onlyTheOwnersUsableUnpostedVideoCanBePosted() {
        UUID venue = data.venue("Band on the Wall " + UUID.randomUUID());
        UUID artist = data.artist("Tagged " + UUID.randomUUID());
        UUID other = data.artist("Other " + UUID.randomUUID());
        UUID gig = data.gig("Lineup night", venue, Instant.parse("2026-08-02T19:30:00Z"), artist);

        // Someone else's clip, an unknown id and an image all get the same answer.
        for (UUID notMine :
                List.of(data.video(viewer.id(), "READY"), UUID.randomUUID(), data.image(author.id(), "READY"))) {
            assertThat(code(api.post("/api/v1/posts", Map.of("clips", List.of(Map.of("mediaId", notMine))), author)))
                    .isEqualTo("MEDIA_INVALID");
        }
        UUID awaiting = data.video(author.id(), "UPLOADED");
        data.setStatus(awaiting, "AWAITING_UPLOAD");
        assertThat(code(api.post("/api/v1/posts", Map.of("clips", List.of(Map.of("mediaId", awaiting))), author)))
                .isEqualTo("MEDIA_NOT_UPLOADED");
        assertThat(code(api.post(
                        "/api/v1/posts",
                        Map.of("clips", List.of(Map.of("mediaId", data.video(author.id(), "REJECTED")))),
                        author)))
                .isEqualTo("MEDIA_NOT_USABLE");

        UUID clip = data.video(author.id(), "READY");
        assertThat(code(api.post(
                        "/api/v1/posts",
                        Map.of("clips", List.of(Map.of("mediaId", clip)), "gigId", UUID.randomUUID()),
                        author)))
                .isEqualTo("GIG_UNKNOWN");
        assertThat(code(api.post(
                        "/api/v1/posts",
                        Map.of("clips", List.of(Map.of("mediaId", clip)), "artistIds", List.of(UUID.randomUUID())),
                        author)))
                .isEqualTo("ARTIST_UNKNOWN");
        assertThat(api.post(
                                "/api/v1/posts",
                                Map.of("clips", List.of(Map.of("mediaId", clip)), "caption", "x".repeat(2201)),
                                author)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.post("/api/v1/posts", Map.of("caption", "no clip"), author)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // Explicit artists win over the gig's bill; duplicates are folded.
        JsonNode post = created(api.post(
                "/api/v1/posts",
                Map.of("clips", List.of(Map.of("mediaId", clip)), "gigId", gig, "artistIds", List.of(other, other)),
                author));
        assertThat(ids(post.path("artists"))).containsExactly(other.toString());

        // One post per clip.
        ResponseEntity<JsonNode> again =
                api.post("/api/v1/posts", Map.of("clips", List.of(Map.of("mediaId", clip))), author);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(again)).isEqualTo("MEDIA_ALREADY_POSTED");
    }

    @Test
    void anIdempotencyKeyReturnsTheSamePostAndRefusesADifferentRequest() {
        UUID clip = data.video(author.id(), "READY");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("clips", List.of(Map.of("mediaId", clip)), "caption", "once");

        JsonNode first = created(postWithKey(body, key));
        ResponseEntity<JsonNode> replay = postWithKey(body, key);

        // The retry (a lost response) is answered with the post created the first time, not a 409.
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().path("id").asText(""))
                .isEqualTo(first.path("id").asText(""));
        assertThat(dsl.fetchCount(dsl.selectFrom("content.post_clip").where("media_id = ?", clip)))
                .isEqualTo(1);

        assertThat(code(postWithKey(Map.of("clips", List.of(Map.of("mediaId", clip)), "caption", "different"), key)))
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(code(postWithKey(body, "not-a-uuid"))).isEqualTo("IDEMPOTENCY_KEY_INVALID");
    }

    @Test
    void listsArePagedNewestFirstAndADeletedPostDisappears() {
        List<String> postIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            postIds.add(created(api.post(
                            "/api/v1/posts",
                            Map.of(
                                    "clips",
                                    List.of(Map.of("mediaId", data.video(author.id(), "READY"))),
                                    "caption",
                                    "clip " + i),
                            author))
                    .path("id")
                    .asText(""));
        }
        String list = "/api/v1/users/" + author.id() + "/posts";

        JsonNode first = api.get(list + "?limit=2", viewer).getBody();
        assertThat(ids(first.path("items"))).containsExactly(postIds.get(2), postIds.get(1));
        JsonNode second = api.get(
                        list + "?limit=2&cursor=" + first.path("nextCursor").asText(""), viewer)
                .getBody();
        assertThat(ids(second.path("items"))).containsExactly(postIds.get(0));
        assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(api.get(list + "?cursor=garbage", viewer).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Only the author may delete; to anyone else the post "does not exist" for that purpose.
        String target = postIds.get(1);
        assertThat(api.delete("/api/v1/posts/" + target, viewer).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.delete("/api/v1/posts/" + target, author).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.get("/api/v1/posts/" + target, author).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ids(pageOf(list, viewer))).containsExactly(postIds.get(2), postIds.get(0));
        assertThat(ids(pageOf("/api/v1/me/posts", author))).containsExactly(postIds.get(2), postIds.get(0));
        assertThat(api.delete("/api/v1/posts/" + target, author).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Lists of things that do not exist are 404s, not empty pages.
        assertThat(api.get("/api/v1/gigs/" + UUID.randomUUID() + "/posts", viewer)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/api/v1/venues/" + UUID.randomUUID() + "/posts", viewer)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/api/v1/posts/" + UUID.randomUUID(), viewer).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anUploadedImageBecomesTheAvatarShownOnTheProfileAndNextToPosts() {
        UUID avatar = data.image(author.id(), "READY");

        ResponseEntity<JsonNode> updated = api.patch("/api/v1/me", Map.of("avatarMediaId", avatar), author);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        String url = updated.getBody().path("avatarUrl").asText("");
        assertThat(url).endsWith("/media/" + avatar + "/v1/image.png");
        assertThat(api.get("/api/v1/users/" + author.handle(), viewer)
                        .getBody()
                        .path("avatarUrl")
                        .asText(""))
                .isEqualTo(url);
        JsonNode post = created(api.post(
                "/api/v1/posts",
                Map.of("clips", List.of(Map.of("mediaId", data.video(author.id(), "READY")))),
                author));
        assertThat(post.path("author").path("avatarUrl").asText("")).isEqualTo(url);

        // Not an image, not ready, or not the caller's: refused with one code.
        Map<String, Object> wrong = new HashMap<>();
        for (UUID bad : List.of(
                data.video(author.id(), "READY"),
                data.image(author.id(), "UPLOADED"),
                data.image(viewer.id(), "READY"))) {
            wrong.put("avatarMediaId", bad);
            assertThat(code(api.patch("/api/v1/me", wrong, author))).isEqualTo("AVATAR_INVALID");
        }
        // Other profile edits leave the avatar alone.
        assertThat(api.patch("/api/v1/me", Map.of("bio", "hello"), author)
                        .getBody()
                        .path("avatarUrl")
                        .asText(""))
                .isEqualTo(url);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private ResponseEntity<JsonNode> postWithKey(Object body, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(author.accessToken());
        headers.set("X-Client", "android");
        headers.set("Idempotency-Key", key);
        return rest.exchange("/api/v1/posts", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    @Test
    void aGigsClipsCanBeNarrowedToOneActOnTheBill() {
        UUID venue = data.venue("Scala " + UUID.randomUUID());
        UUID headliner = data.artist("Keo " + UUID.randomUUID());
        UUID support = data.artist("Cardinals " + UUID.randomUUID());
        UUID gig = data.gig("Keo", venue, Instant.parse("2026-03-01T19:30:00Z"), headliner, support);
        // Three fans: one fan's clips from a gig are one post.
        TestApi.User second = api.register("second");
        TestApi.User third = api.register("third");
        String supportSet = created(api.post(
                        "/api/v1/posts",
                        Map.of(
                                "clips",
                                List.of(Map.of("mediaId", data.video(author.id(), "READY"))),
                                "gigId",
                                gig,
                                "artistIds",
                                List.of(support)),
                        author))
                .path("id")
                .asText("");
        String headlineSet = created(api.post(
                        "/api/v1/posts",
                        Map.of(
                                "clips",
                                List.of(Map.of("mediaId", data.video(second.id(), "READY"))),
                                "gigId",
                                gig,
                                "artistIds",
                                List.of(headliner)),
                        second))
                .path("id")
                .asText("");
        // No artists given: tagged with the whole bill, so it is in both acts' lists.
        String wholeNight = created(api.post(
                        "/api/v1/posts",
                        Map.of("clips", List.of(Map.of("mediaId", data.video(third.id(), "READY"))), "gigId", gig),
                        third))
                .path("id")
                .asText("");

        String clips = "/api/v1/gigs/" + gig + "/posts";
        assertThat(items(clips, viewer)).containsExactly(wholeNight, headlineSet, supportSet);
        assertThat(items(clips + "?artistId=" + support, viewer)).containsExactly(wholeNight, supportSet);
        assertThat(items(clips + "?artistId=" + headliner, viewer)).containsExactly(wholeNight, headlineSet);
        assertThat(items(clips + "?artistId=" + UUID.randomUUID(), viewer)).isEmpty();
    }

    private JsonNode pageOf(String path, TestApi.User user) {
        ResponseEntity<JsonNode> response = api.get(path, user);
        assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return response.getBody().path("items");
    }

    private List<String> items(String path, TestApi.User user) {
        return ids(pageOf(path, user));
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.path("id").asText("")));
        return ids;
    }

    private static String code(ResponseEntity<JsonNode> response) {
        return response.getBody().path("code").asText("");
    }

    private static JsonNode created(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
