package app.soundcheck.media;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.content.internal.PostService;
import app.soundcheck.media.internal.MediaObjectPurge;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.MutableClock;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Media hygiene against real PostGIS and LocalStack S3: the same file twice is refused with the
 * post it is already in (and allowed again once that post is deleted, for another user, or without a
 * hash); deleting a post releases its clips' and previews' media, hiding it does not; and a week later
 * the purge removes the released media's files from storage and keeps the rows.
 */
class MediaHygieneIT extends IntegrationTest {

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
    ObjectStorage storage;

    @Autowired
    PostService postService;

    @Autowired
    MediaObjectPurge purge;

    @Autowired
    MutableClock clock;

    TestApi api;
    TestData data;
    TestApi.User fan;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        fan = api.register("hygiene");
    }

    @AfterEach
    void backToRealTime() {
        clock.reset();
    }

    // ── The same file twice ──────────────────────────────────────────────────────────────────────

    @Test
    void theSameFileTwiceIsRefusedWithThePostItIsInUntilThatPostIsDeleted() {
        String hash = md5();
        UUID first = UUID.fromString(open(fan, hash).getBody().path("mediaId").asText(""));
        data.setStatus(first, "READY");
        String postId = post(fan, clip(first, null));

        ResponseEntity<JsonNode> again = open(fan, hash);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().path("code").asText("")).isEqualTo("DUPLICATE_UPLOAD");
        assertThat(again.getBody().path("postId").asText("")).isEqualTo(postId);
        assertThat(count("owner_id = ? and content_hash = ?", fan.id(), hash))
                .as("nothing was created")
                .isEqualTo(1);

        // A hidden post still holds the clip: re-posting it is what the guard is for.
        postService.hide(UUID.fromString(postId));
        assertThat(open(fan, hash).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        postService.restore(UUID.fromString(postId));

        // Someone else's identical file, and an upload without a hash, are none of the guard's business.
        TestApi.User other = api.register("hygieneb");
        assertThat(open(other, hash).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(open(fan, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Once the post is deleted, the file may be posted again.
        assertThat(api.delete("/api/v1/posts/" + postId, fan).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(open(fan, hash).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aMatchInNoPostIsAnAbandonedUploadAndIsAllowed() {
        String hash = md5();
        assertThat(open(fan, hash).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(open(fan, hash).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aHashMustSayWhichAlgorithmMadeIt() {
        for (String bad : List.of("d41d8cd98f00b204e9800998ecf8427e", "md5:XYZ", "sha1:" + "a".repeat(40))) {
            ResponseEntity<JsonNode> refused = open(fan, bad);
            assertThat(refused.getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(open(fan, "sha256:" + "ab".repeat(32)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ── The media of deleted posts ───────────────────────────────────────────────────────────────

    @Test
    void deletingAPostReleasesItsMediaAndAWeekLaterThePurgeRemovesTheFiles(@TempDir Path dir) throws Exception {
        UUID video = data.video(fan.id(), "READY");
        UUID preview = data.image(fan.id(), "READY");
        String postId = post(fan, clip(video, preview));
        List<String> keys = stored(dir, video, preview);

        assertThat(api.delete("/api/v1/posts/" + postId, fan).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        for (UUID id : List.of(video, preview)) {
            Record row = asset(id);
            assertThat(row.get("status")).as("released").isEqualTo("DELETED");
            assertThat(row.get("deleted_at")).isNotNull();
            assertThat(row.get("objects_purged_at")).isNull();
        }
        // Nothing leaves storage for a week.
        purge.purgeDue();
        clock.advance(MediaObjectPurge.RETENTION.minusHours(1));
        purge.purgeDue();
        keys.forEach(key -> assertThat(storage.head(key)).as(key).isPresent());

        clock.advance(Duration.ofDays(1).plusHours(1)); // 8 days after the delete
        assertThat(purge.purgeDue()).isGreaterThanOrEqualTo(2);
        keys.forEach(key -> assertThat(storage.head(key)).as(key).isEmpty());
        for (UUID id : List.of(video, preview)) {
            Record row = asset(id);
            assertThat(row.get("objects_purged_at")).as("the row stays, marked").isNotNull();
        }
        // The post's clip still points at its (now file-less) media.
        assertThat(dsl.fetchCount(dsl.selectFrom("content.post_clip").where("media_id = ?", video)))
                .isEqualTo(1);
    }

    @Test
    void aHiddenPostKeepsItsMedia(@TempDir Path dir) throws Exception {
        UUID video = data.video(fan.id(), "READY");
        String postId = post(fan, clip(video, null));
        List<String> keys = stored(dir, video);

        assertThat(postService.hide(UUID.fromString(postId))).isTrue();
        clock.advance(Duration.ofDays(8));
        purge.purgeDue();

        assertThat(asset(video).get("status")).isEqualTo("READY");
        keys.forEach(key -> assertThat(storage.head(key)).as(key).isPresent());
    }

    @Test
    void anAbortedUploadIsPurgedToo() {
        UUID aborted = UUID.fromString(open(fan, null).getBody().path("mediaId").asText(""));
        assertThat(api.delete("/api/v1/media/uploads/" + aborted, fan).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(asset(aborted).get("deleted_at")).isNotNull();

        clock.advance(Duration.ofDays(8));
        purge.purgeDue();

        assertThat(asset(aborted).get("objects_purged_at")).isNotNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** {@code POST /media/uploads} for a small video, with this hash (or none). */
    private ResponseEntity<JsonNode> open(TestApi.User user, String contentHash) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "VIDEO");
        body.put("contentType", "video/mp4");
        body.put("bytes", 300_000);
        body.put("durationMs", 12_000);
        body.put("contentHash", contentHash);
        return api.post("/api/v1/media/uploads", body, user);
    }

    private String post(TestApi.User user, Map<String, Object> clip) {
        ResponseEntity<JsonNode> created = api.post("/api/v1/posts", Map.of("clips", List.of(clip)), user);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().path("id").asText("");
    }

    private static Map<String, Object> clip(UUID mediaId, UUID previewMediaId) {
        Map<String, Object> clip = new HashMap<>();
        clip.put("mediaId", mediaId);
        clip.put("previewMediaId", previewMediaId);
        return clip;
    }

    /** Puts an original and some served files into storage for each asset; returns their keys. */
    private List<String> stored(Path dir, UUID... mediaIds) throws Exception {
        Path file = Files.write(dir.resolve("bytes"), new byte[] {1, 2, 3});
        List<String> keys = new java.util.ArrayList<>();
        for (UUID id : mediaIds) {
            keys.add("uploads/" + fan.id() + "/" + id + "/original");
            keys.add("media/" + id + "/v1/master.m3u8");
            keys.add("media/" + id + "/v1/720p/seg_000.m4s");
            keys.add("media/" + id + "/v1/poster.jpg");
        }
        for (String key : keys) {
            storage.putFile(key, file, "application/octet-stream");
        }
        return keys;
    }

    private Record asset(UUID id) {
        return dsl.fetchSingle("select status, deleted_at, objects_purged_at from media.media_asset where id = ?", id);
    }

    private int count(String where, Object... args) {
        return dsl.fetchCount(dsl.selectFrom("media.media_asset").where(where, args));
    }

    private static String md5() {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "md5:" + HexFormat.of().formatHex(bytes);
    }
}
