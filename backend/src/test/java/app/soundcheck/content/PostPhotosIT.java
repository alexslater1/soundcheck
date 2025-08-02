package app.soundcheck.content;

import static app.soundcheck.content.PostClipsIT.clip;
import static app.soundcheck.content.PostClipsIT.created;
import static app.soundcheck.content.PostClipsIT.ok;
import static app.soundcheck.content.PostClipsIT.statuses;
import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
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
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Over HTTP against real PostGIS: a post's slot holds a photo as well as a video. A photo is
 * READY once its upload is complete, so it makes its post live at once; it carries its act like a clip,
 * so it is on that act's page; it marks its owner Went like a clip; the feed takes the
 * post as a candidate; and only the owner's own, uploaded photo can fill a slot.
 */
class PostPhotosIT extends IntegrationTest {

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
    TestApi.User fan;
    TestApi.User viewer;
    UUID headliner;
    UUID support;
    UUID gig;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        fan = api.register("photos");
        viewer = api.register("looker");
        String tag = UUID.randomUUID().toString().substring(0, 8);
        headliner = data.artist("Keo " + tag);
        support = data.artist("Cardinals " + tag);
        gig = data.gig(
                "Keo " + tag, data.venue("Scala " + tag), Instant.parse("2026-08-01T19:30:00Z"), headliner, support);
    }

    @Test
    void aPhotoMakesItsPostLiveAtOnceAndCarriesItsAct() {
        UUID photo = data.image(fan.id(), "READY");

        JsonNode post = created(
                api.post("/api/v1/posts", Map.of("clips", List.of(clip(photo, support, null)), "gigId", gig), fan));

        // No transcoding to wait for: live straight away, the photo as its cover.
        assertThat(post.path("status").asText("")).isEqualTo("PUBLISHED");
        JsonNode cover = post.path("playback");
        assertThat(cover.path("kind").asText("")).isEqualTo("IMAGE");
        assertThat(cover.path("hlsUrl").isNull()).isTrue();
        assertThat(cover.path("posterUrl").asText("")).endsWith("/media/" + photo + "/v1/image.png");
        assertThat(cover.path("hasAudio").isNull()).isTrue();
        assertThat(post.path("clips").get(0).path("act").path("id").asText("")).isEqualTo(support.toString());

        String postId = post.path("id").asText("");
        // Everyone sees it; it is on its act's page and the gig's list for that act, not the other act's.
        assertThat(api.get("/api/v1/posts/" + postId, viewer).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(ok(api.get("/api/v1/artists/" + support + "/posts", viewer))))
                .containsExactly(postId);
        assertThat(ids(ok(api.get("/api/v1/artists/" + headliner + "/posts", viewer))))
                .isEmpty();
        assertThat(ids(ok(api.get("/api/v1/gigs/" + gig + "/posts?artistId=" + support, viewer))))
                .containsExactly(postId);
        // A live photo from the gig marks its owner Went, as a clip does.
        assertThat(ok(api.get("/api/v1/gigs/" + gig, fan))
                        .path("gig")
                        .path("myAttendance")
                        .asText(""))
                .isEqualTo("WENT");
        // The feed takes it as a candidate (a READY item in a published post).
        assertThat(dsl.fetchExists(
                        dsl.selectOne().from("feed.post_candidate").where("post_id = ?", UUID.fromString(postId))))
                .isTrue();
    }

    @Test
    void photosAndVideosShareAPostAndAPhotoFillsAWaitingSlot() {
        UUID video = data.video(fan.id(), "UPLOADED");
        JsonNode post = created(api.post(
                "/api/v1/posts",
                Map.of("clips", List.of(clip(video, headliner, null), clip(null, headliner, null)), "gigId", gig),
                fan));
        String postId = post.path("id").asText("");
        assertThat(post.path("status").asText("")).isEqualTo("PENDING_MEDIA");

        // The photo's upload completes while the video is still being transcoded: the post goes live.
        UUID photo = data.image(fan.id(), "READY");
        String slot = post.path("clips").get(1).path("id").asText("");
        JsonNode live = ok(api.patch("/api/v1/posts/" + postId + "/clips/" + slot, Map.of("mediaId", photo), fan));
        assertThat(live.path("status").asText("")).isEqualTo("PUBLISHED");
        assertThat(statuses(live)).containsExactly("PROCESSING", "READY");
        assertThat(live.path("playback").path("kind").asText(""))
                .as("the cover is the first item that can be shown")
                .isEqualTo("IMAGE");
        assertThat(live.path("clips").get(0).path("playback").path("kind").asText(""))
                .isEqualTo("VIDEO");
        assertThat(live.path("clipCount").asInt()).isEqualTo(2);
    }

    @Test
    void onlyTheOwnersUploadedPhotoFillsASlot() {
        // Someone else's photo is refused like someone else's clip.
        assertThat(api.post(
                                "/api/v1/posts",
                                Map.of("clips", List.of(clip(data.image(viewer.id(), "READY"), headliner, null))),
                                fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("MEDIA_INVALID");
        // A photo whose upload is not complete, or that was refused, cannot be posted.
        UUID awaiting = data.image(fan.id(), "UPLOADED");
        data.setStatus(awaiting, "AWAITING_UPLOAD");
        assertThat(api.post("/api/v1/posts", Map.of("clips", List.of(clip(awaiting, headliner, null))), fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("MEDIA_NOT_UPLOADED");
        assertThat(api.post(
                                "/api/v1/posts",
                                Map.of("clips", List.of(clip(data.image(fan.id(), "REJECTED"), headliner, null))),
                                fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("MEDIA_NOT_USABLE");
        // And a posted photo cannot be posted again.
        UUID photo = data.image(fan.id(), "READY");
        created(api.post("/api/v1/posts", Map.of("clips", List.of(clip(photo, headliner, null))), fan));
        assertThat(api.post("/api/v1/posts", Map.of("clips", List.of(clip(photo, headliner, null))), fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("MEDIA_ALREADY_POSTED");
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asText("")));
        return ids;
    }
}
