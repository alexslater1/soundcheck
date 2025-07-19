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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Over HTTP against real PostGIS: a post is one fan's clips from one gig and day. Clips are announced
 * as slots and filled as uploads complete; the post goes live with its first playable clip and shows the
 * rest as "on its way"; a second post from the same gig and day joins the first; limits per act; clips
 * grouped by act; failed clips only for their owner; slots that never get a clip expire; the author's
 * edit.
 */
class PostClipsIT extends IntegrationTest {

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
    TestApi.User fan;
    TestApi.User viewer;
    UUID headliner;
    UUID support;
    UUID gig;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        fan = api.register("clips");
        viewer = api.register("watcher");
        String tag = UUID.randomUUID().toString().substring(0, 8);
        headliner = data.artist("Keo " + tag);
        support = data.artist("Cardinals " + tag);
        gig = data.gig(
                "Keo " + tag, data.venue("Scala " + tag), Instant.parse("2026-08-01T19:30:00Z"), headliner, support);
    }

    @Test
    void aPostGoesLiveWithItsFirstPlayableClipAndShowsTheOthersAsOnTheirWay() {
        UUID preview = data.image(fan.id(), "READY");
        JsonNode created = created(api.post(
                "/api/v1/posts",
                Map.of(
                        "clips",
                        List.of(clip(null, support, preview), clip(null, headliner, null), clip(null, headliner, null)),
                        "gigId",
                        gig),
                fan));
        String postId = created.path("id").asText("");
        assertThat(created.path("status").asText("")).isEqualTo("PENDING_MEDIA");
        assertThat(statuses(created)).containsExactly("WAITING", "WAITING", "WAITING");
        assertThat(created.path("clips").get(0).path("previewUrl").asText("")).isNotBlank();
        assertThat(api.get("/api/v1/posts/" + postId, viewer).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // The second clip's upload completes first, already transcoded: the post goes live with it.
        List<String> slots = ids(created.path("clips"));
        UUID ready = data.video(fan.id(), "READY");
        JsonNode live =
                ok(api.patch("/api/v1/posts/" + postId + "/clips/" + slots.get(1), Map.of("mediaId", ready), fan));
        assertThat(live.path("status").asText("")).isEqualTo("PUBLISHED");
        assertThat(live.path("playback").path("status").asText(""))
                .as("the cover is the playable clip")
                .isEqualTo("READY");

        // Everyone sees the post with its other slots on their way, in act order (support first).
        JsonNode seen = ok(api.get("/api/v1/posts/" + postId, viewer));
        assertThat(statuses(seen)).containsExactly("WAITING", "READY", "WAITING");
        assertThat(seen.path("clipCount").asInt()).isEqualTo(3);
        assertThat(seen.path("clips").get(0).path("act").path("id").asText("")).isEqualTo(support.toString());

        // The first clip arrives still processing, then the worker finishes it.
        UUID processing = data.video(fan.id(), "PROCESSING");
        ok(api.patch("/api/v1/posts/" + postId + "/clips/" + slots.get(0), Map.of("mediaId", processing), fan));
        assertThat(statuses(ok(api.get("/api/v1/posts/" + postId, viewer))))
                .containsExactly("PROCESSING", "READY", "WAITING");
        data.workerFinishes(processing, fan.id());
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(statuses(ok(api.get("/api/v1/posts/" + postId, viewer))))
                        .containsExactly("READY", "READY", "WAITING"));
        assertThat(ok(api.get("/api/v1/posts/" + postId, viewer))
                        .path("clips")
                        .get(0)
                        .path("previewUrl")
                        .isNull())
                .as("a playable clip needs no preview")
                .isTrue();

        // Attaching the same clip again is a retry; another clip, or someone else's slot, is refused.
        assertThat(api.patch("/api/v1/posts/" + postId + "/clips/" + slots.get(1), Map.of("mediaId", ready), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.patch(
                                "/api/v1/posts/" + postId + "/clips/" + slots.get(1),
                                Map.of("mediaId", data.video(fan.id(), "READY")),
                                fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(api.patch(
                                "/api/v1/posts/" + postId + "/clips/" + slots.get(2),
                                Map.of("mediaId", data.video(viewer.id(), "READY")),
                                viewer)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // A clip already in a post cannot fill another slot.
        assertThat(api.patch("/api/v1/posts/" + postId + "/clips/" + slots.get(2), Map.of("mediaId", ready), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aSecondPostFromTheSameGigAndDayJoinsTheFirstAndKeepsItsCaption() {
        String first = created(api.post(
                        "/api/v1/posts",
                        Map.of(
                                "clips",
                                List.of(clip(data.video(fan.id(), "READY"), headliner, null)),
                                "gigId",
                                gig,
                                "caption",
                                "What a night"),
                        fan))
                .path("id")
                .asText("");
        JsonNode again = created(api.post(
                "/api/v1/posts",
                Map.of(
                        "clips",
                        List.of(clip(data.video(fan.id(), "READY"), support, null)),
                        "gigId",
                        gig,
                        "caption",
                        "More"),
                fan));
        assertThat(again.path("id").asText("")).isEqualTo(first);
        assertThat(again.path("caption").asText("")).isEqualTo("What a night");
        assertThat(again.path("clipCount").asInt()).isEqualTo(2);

        // The next day of the gig (a festival) is its own post; a day too late is refused.
        String dayTwo = created(api.post(
                        "/api/v1/posts",
                        Map.of(
                                "clips",
                                List.of(clip(data.video(fan.id(), "READY"), null, null)),
                                "gigId",
                                gig,
                                "day",
                                "2026-08-02"),
                        fan))
                .path("id")
                .asText("");
        assertThat(dayTwo).isNotEqualTo(first);
        assertThat(code(api.post(
                        "/api/v1/posts",
                        Map.of(
                                "clips",
                                List.of(clip(data.video(fan.id(), "READY"), null, null)),
                                "gigId",
                                gig,
                                "day",
                                "2026-08-05"),
                        fan)))
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // Another fan's clips from the same night are their own post.
        TestApi.User friend = api.register("friend");
        assertThat(created(api.post(
                                "/api/v1/posts",
                                Map.of(
                                        "clips",
                                        List.of(clip(data.video(friend.id(), "READY"), null, null)),
                                        "gigId",
                                        gig),
                                friend))
                        .path("id")
                        .asText(""))
                .isNotEqualTo(first);
        assertThat(ids(ok(api.get("/api/v1/gigs/" + gig + "/posts", viewer)).path("items")))
                .hasSize(3);
    }

    @Test
    void tenClipsPerPersonPerActAndGroupsInTheOrderTheyFirstAppear() {
        List<Map<String, Object>> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(clip(null, headliner, null));
        }
        assertThat(api.post("/api/v1/posts", Map.of("clips", eleven, "gigId", gig), fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("TOO_MANY_CLIPS");

        // Ten of each act, and ten with no act, are fine.
        List<Map<String, Object>> clips = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            clips.add(clip(null, support, null));
            clips.add(clip(null, headliner, null));
        }
        clips.add(clip(data.video(fan.id(), "READY"), null, null));
        JsonNode post = created(api.post("/api/v1/posts", Map.of("clips", clips, "gigId", gig), fan));
        // Interleaved when announced; each act's clips together, acts in the order they first appeared.
        List<String> acts = new ArrayList<>();
        post.path("clips")
                .forEach(c -> acts.add(
                        c.path("act").isNull()
                                ? "none"
                                : c.path("act").path("id").asText("")));
        assertThat(acts.subList(0, 10)).containsOnly(support.toString());
        assertThat(acts.subList(10, 20)).containsOnly(headliner.toString());
        assertThat(acts.get(20)).isEqualTo("none");
        // The post's acts are its clips' acts (and, with none given, the gig's bill).
        assertThat(post.path("artists").size()).isEqualTo(2);
        // One more headliner clip of the fan's is one too many, even added later.
        assertThat(api.post(
                                "/api/v1/posts/" + post.path("id").asText("") + "/clips",
                                Map.of("clips", List.of(clip(null, headliner, null))),
                                fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aFailedClipIsShownOnlyToItsOwnerAndAPostFailsOnlyWhenNoClipCanPlay() {
        UUID good = data.video(fan.id(), "PROCESSING");
        UUID bad = data.video(fan.id(), "PROCESSING");
        JsonNode post = created(api.post(
                "/api/v1/posts",
                Map.of("clips", List.of(clip(bad, null, null), clip(good, null, null)), "gigId", gig),
                fan));
        String postId = post.path("id").asText("");

        data.workerGivesUp(bad, fan.id(), true);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(statuses(ok(api.get("/api/v1/posts/" + postId, fan))))
                        .containsExactly("FAILED", "PROCESSING"));
        assertThat(ok(api.get("/api/v1/posts/" + postId, fan)).path("status").asText(""))
                .as("another clip can still play")
                .isEqualTo("PENDING_MEDIA");

        data.workerFinishes(good, fan.id());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                        api.get("/api/v1/posts/" + postId, viewer).getStatusCode())
                .isEqualTo(HttpStatus.OK));
        assertThat(statuses(ok(api.get("/api/v1/posts/" + postId, viewer)))).containsExactly("READY");
        assertThat(statuses(ok(api.get("/api/v1/posts/" + postId, fan)))).containsExactly("FAILED", "READY");

        // A post whose every clip fails is FAILED.
        UUID only = data.video(fan.id(), "PROCESSING");
        String doomed = created(api.post("/api/v1/posts", Map.of("clips", List.of(clip(only, null, null))), fan))
                .path("id")
                .asText("");
        data.workerGivesUp(only, fan.id(), false);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(ok(api.get("/api/v1/posts/" + doomed, fan))
                                .path("status")
                                .asText(""))
                        .isEqualTo("FAILED"));
    }

    @Test
    void aSlotWhoseClipNeverArrivesIsDroppedAfterADay() {
        JsonNode post = created(api.post(
                "/api/v1/posts",
                Map.of(
                        "clips",
                        List.of(clip(data.video(fan.id(), "READY"), null, null), clip(null, null, null)),
                        "gigId",
                        gig),
                fan));
        String postId = post.path("id").asText("");
        String lonely = created(api.post("/api/v1/posts", Map.of("clips", List.of(clip(null, null, null))), fan))
                .path("id")
                .asText("");
        dsl.execute(
                "update content.post_clip set created_at = now() - interval '25 hours' where post_id in (?::uuid, ?::uuid)"
                        + " and media_id is null",
                postId,
                lonely);

        postService.reconcilePending(Instant.now(), 100);

        assertThat(statuses(ok(api.get("/api/v1/posts/" + postId, viewer)))).containsExactly("READY");
        assertThat(ok(api.get("/api/v1/posts/" + lonely, fan)).path("status").asText(""))
                .as("nothing ever arrived")
                .isEqualTo("FAILED");
    }

    @Test
    void theAuthorEditsTheCaptionTheOrderAndTheClipsButNeverRemovesTheLastOne() {
        JsonNode post = created(api.post(
                "/api/v1/posts",
                Map.of(
                        "clips",
                        List.of(
                                clip(data.video(fan.id(), "READY"), headliner, null),
                                clip(data.video(fan.id(), "READY"), headliner, null),
                                clip(data.video(fan.id(), "READY"), headliner, null)),
                        "gigId",
                        gig,
                        "caption",
                        "first try"),
                fan));
        String postId = post.path("id").asText("");
        List<String> clips = ids(post.path("clips"));

        // Someone else cannot edit it.
        assertThat(api.patch("/api/v1/posts/" + postId, Map.of("caption", "mine now"), viewer)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        Map<String, Object> edit = new HashMap<>();
        edit.put("caption", "  Better caption ");
        edit.put(
                "clips",
                List.of(
                        Map.of("id", clips.get(2), "artistId", support.toString()),
                        Map.of("id", clips.get(0), "artistId", headliner.toString())));
        JsonNode edited = ok(api.patch("/api/v1/posts/" + postId, edit, fan));
        assertThat(edited.path("caption").asText("")).isEqualTo("Better caption");
        assertThat(ids(edited.path("clips"))).containsExactly(clips.get(2), clips.get(0));
        assertThat(edited.path("clips").get(0).path("act").path("id").asText(""))
                .isEqualTo(support.toString());

        // An empty caption clears it; removing a clip works until one is left.
        ok(api.patch("/api/v1/posts/" + postId, Map.of("caption", ""), fan));
        assertThat(ok(api.get("/api/v1/posts/" + postId, viewer))
                        .path("caption")
                        .isNull())
                .isTrue();
        assertThat(api.delete("/api/v1/posts/" + postId + "/clips/" + clips.get(2), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.delete("/api/v1/posts/" + postId + "/clips/" + clips.get(0), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(ids(ok(api.get("/api/v1/posts/" + postId, viewer)).path("clips")))
                .containsExactly(clips.get(0));

        // The author adds a clip to the live post later.
        JsonNode added = ok(api.post(
                "/api/v1/posts/" + postId + "/clips",
                Map.of("clips", List.of(clip(data.video(fan.id(), "READY"), headliner, null))),
                fan));
        assertThat(added.path("clipCount").asInt()).isEqualTo(2);
    }

    @Test
    void movingAPostToAnotherGigMovesTheWentItsClipsMade() {
        UUID other = data.gig(
                "Other night",
                data.venue("Lexington " + UUID.randomUUID()),
                Instant.parse("2026-08-10T19:30:00Z"),
                support);
        String postId = created(api.post(
                        "/api/v1/posts",
                        Map.of("clips", List.of(clip(data.video(fan.id(), "READY"), null, null)), "gigId", gig),
                        fan))
                .path("id")
                .asText("");
        assertThat(attendance(gig)).isEqualTo("WENT");

        JsonNode moved = ok(api.patch("/api/v1/posts/" + postId, Map.of("gigId", other.toString()), fan));
        assertThat(moved.path("gig").path("id").asText("")).isEqualTo(other.toString());
        assertThat(moved.path("day").asText("")).isEqualTo("2026-08-10");
        assertThat(attendance(other)).isEqualTo("WENT");
        assertThat(attendance(gig)).as("only the post made it").isNull();

        // Moving onto a gig and day the fan already has a post from is refused.
        created(api.post(
                "/api/v1/posts",
                Map.of("clips", List.of(clip(data.video(fan.id(), "READY"), null, null)), "gigId", gig),
                fan));
        assertThat(api.patch("/api/v1/posts/" + postId, Map.of("gigId", gig.toString()), fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("POST_EXISTS");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    static Map<String, Object> clip(UUID mediaId, UUID artistId, UUID previewMediaId) {
        Map<String, Object> clip = new HashMap<>();
        clip.put("mediaId", mediaId);
        clip.put("artistId", artistId);
        clip.put("previewMediaId", previewMediaId);
        return clip;
    }

    private String attendance(UUID gigId) {
        JsonNode mine =
                api.get("/api/v1/gigs/" + gigId, fan).getBody().path("gig").path("myAttendance");
        return mine.isNull() ? null : mine.asText("");
    }

    static List<String> statuses(JsonNode post) {
        List<String> statuses = new ArrayList<>();
        post.path("clips").forEach(c -> statuses.add(c.path("status").asText("")));
        return statuses;
    }

    static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.path("id").asText("")));
        return ids;
    }

    static JsonNode created(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    static HttpStatus code(ResponseEntity<JsonNode> response) {
        return HttpStatus.valueOf(response.getStatusCode().value());
    }
}
