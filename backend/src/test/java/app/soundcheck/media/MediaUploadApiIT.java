package app.soundcheck.media;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The upload API against LocalStack S3: single PUT and 3-part multipart happy paths, an oversized
 * upload rejected and deleted, complete twice, idempotency replay and key reuse, and non-owners seeing 404.
 * The test profile sets the video limit to 20 MB so "too large" can be exercised with real bytes.
 */
class MediaUploadApiIT extends IntegrationTest {

    static final int MIB = 1024 * 1024;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectStorage storage;

    @Autowired
    DSLContext dsl;

    TestApi api;
    TestApi.User owner;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        owner = api.register("uploader");
    }

    @Test
    void aSmallVideoIsOnePutThenCompleteAndCompletingAgainChangesNothing() throws Exception {
        byte[] bytes = payload(300_000);
        ResponseEntity<JsonNode> created = api.post(
                "/api/v1/media/uploads",
                Map.of(
                        "kind",
                        "VIDEO",
                        "contentType",
                        "video/mp4",
                        "bytes",
                        bytes.length,
                        "durationMs",
                        12_000,
                        "width",
                        1280,
                        "height",
                        720,
                        "clientMeta",
                        Map.of("platform", "android", "originalBytes", 900_000, "compressedBytes", bytes.length)),
                owner);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode ticket = created.getBody();
        assertThat(ticket.path("strategy").asText("")).isEqualTo("SINGLE");
        assertThat(ticket.path("headers").path("Content-Type").asText("")).isEqualTo("video/mp4");
        assertThat(ticket.path("parts").isNull()).isTrue();
        assertThat(ticket.has("expiresAt")).isTrue();
        String mediaId = ticket.path("mediaId").asText("");

        // Before any bytes arrive, complete is refused and the owner sees AWAITING_UPLOAD.
        assertThat(api.post("/api/v1/media/uploads/" + mediaId + "/complete", null, owner)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("UPLOAD_INCOMPLETE");
        assertThat(ok(api.get("/api/v1/media/" + mediaId, owner)).path("status").asText(""))
                .isEqualTo("AWAITING_UPLOAD");

        assertThat(put(ticket.path("putUrl").asText(""), "video/mp4", bytes).statusCode())
                .isEqualTo(200);

        ResponseEntity<JsonNode> completed = api.post("/api/v1/media/uploads/" + mediaId + "/complete", null, owner);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(completed.getBody().path("status").asText("")).isEqualTo("UPLOADED");

        // Complete twice → the same 202.
        ResponseEntity<JsonNode> again = api.post("/api/v1/media/uploads/" + mediaId + "/complete", null, owner);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(again.getBody().path("status").asText("")).isEqualTo("UPLOADED");

        var row = dsl.fetchSingle(
                "select status, actual_bytes, original_key, upload_strategy, client_meta->>'platform' as platform,"
                        + " uploaded_at is not null as uploaded from media.media_asset where id = ?",
                UUID.fromString(mediaId));
        assertThat(row.get("status")).isEqualTo("UPLOADED");
        assertThat(row.get("actual_bytes")).isEqualTo((long) bytes.length);
        // The key is built from ids only (no client-supplied name).
        assertThat(row.get("original_key")).isEqualTo("uploads/" + owner.id() + "/" + mediaId + "/original");
        assertThat(row.get("platform")).isEqualTo("android");
        assertThat(row.get("uploaded")).isEqualTo(true);

        JsonNode playback = ok(api.get("/api/v1/media/" + mediaId, owner));
        assertThat(playback.path("status").asText("")).isEqualTo("UPLOADED");
        assertThat(playback.path("hlsUrl").isNull()).isTrue(); // nothing to play until the worker is done
        assertThat(playback.path("durationMs").asInt()).isEqualTo(12_000);

        // An upload that is already in cannot be aborted.
        assertThat(api.delete("/api/v1/media/uploads/" + mediaId, owner)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("UPLOAD_NOT_ABORTABLE");
    }

    @Test
    void aLargerVideoIsUploadedInThreePartsAndCompletedWithTheirEtags() throws Exception {
        int total = 17 * MIB; // parts of 8 MiB: 8 + 8 + 1
        JsonNode ticket = createVideo(total);
        assertThat(ticket.path("strategy").asText("")).isEqualTo("MULTIPART");
        assertThat(ticket.path("partSize").asInt()).isEqualTo(8 * MIB);
        assertThat(ticket.path("parts").size()).isEqualTo(3);
        assertThat(ticket.path("putUrl").isNull()).isTrue();
        String mediaId = ticket.path("mediaId").asText("");

        List<Map<String, Object>> etags = uploadParts(ticket, new int[] {8 * MIB, 8 * MIB, MIB});

        // Without the parts' ETags a multipart upload cannot be completed.
        assertThat(api.post("/api/v1/media/uploads/" + mediaId + "/complete", Map.of(), owner)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("PARTS_REQUIRED");
        // The parts may arrive in any order; the API sorts them.
        List<Map<String, Object>> shuffled = List.of(etags.get(2), etags.get(0), etags.get(1));
        ResponseEntity<JsonNode> completed =
                api.post("/api/v1/media/uploads/" + mediaId + "/complete", Map.of("parts", shuffled), owner);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(completed.getBody().path("status").asText("")).isEqualTo("UPLOADED");

        assertThat(storage.head("uploads/" + owner.id() + "/" + mediaId + "/original"))
                .hasValueSatisfying(head -> assertThat(head.contentLength()).isEqualTo(total));
        // Again, now without parts: still the same answer.
        assertThat(api.post("/api/v1/media/uploads/" + mediaId + "/complete", null, owner)
                        .getBody()
                        .path("status")
                        .asText(""))
                .isEqualTo("UPLOADED");
    }

    @Test
    void anUploadLargerThanTheLimitIsRejectedAndItsObjectDeleted() throws Exception {
        // Declared 18 MiB (under the 20 MB test limit), but 24 MiB are actually uploaded.
        JsonNode ticket = createVideo(18 * MIB);
        String mediaId = ticket.path("mediaId").asText("");
        List<Map<String, Object>> etags = uploadParts(ticket, new int[] {8 * MIB, 8 * MIB, 8 * MIB});

        ResponseEntity<JsonNode> completed =
                api.post("/api/v1/media/uploads/" + mediaId + "/complete", Map.of("parts", etags), owner);

        assertThat(completed.getStatusCode().value()).isEqualTo(413);
        assertThat(completed.getBody().path("code").asText("")).isEqualTo("MEDIA_TOO_LARGE");
        // The REJECTED status survived the error response, and the object is gone.
        assertThat(ok(api.get("/api/v1/media/" + mediaId, owner)).path("status").asText(""))
                .isEqualTo("REJECTED");
        assertThat(storage.head("uploads/" + owner.id() + "/" + mediaId + "/original"))
                .isEmpty();
        assertThat(api.post("/api/v1/media/uploads/" + mediaId + "/complete", Map.of("parts", etags), owner)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("MEDIA_NOT_UPLOADABLE");

        // A declared size over the limit is refused before anything is created.
        ResponseEntity<JsonNode> declared = api.post(
                "/api/v1/media/uploads", Map.of("kind", "VIDEO", "contentType", "video/mp4", "bytes", 21 * MIB), owner);
        assertThat(declared.getStatusCode().value()).isEqualTo(413);
    }

    @Test
    void anIdempotencyKeyReplaysTheSameUploadAndRefusesADifferentRequest() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("kind", "VIDEO", "contentType", "video/mp4", "bytes", 1234);

        JsonNode first = created(postWithKey(body, key));
        JsonNode replay = created(postWithKey(body, key));
        assertThat(replay.path("mediaId").asText(""))
                .isEqualTo(first.path("mediaId").asText(""));
        assertThat(replay.path("putUrl").asText("")).startsWith("http"); // freshly signed; URLs are never stored
        assertThat(dsl.fetchValue("select count(*) from media.media_asset where owner_id = ?", owner.id()))
                .isEqualTo(1L);
        assertThat(dsl.fetchValue(
                        "select response_body::text from shared.idempotency_key where user_id = ? and key = ?",
                        owner.id(),
                        key))
                .asString()
                .contains(first.path("mediaId").asText(""))
                .doesNotContain("X-Amz-Signature");

        // Same key, different body → 409; and nothing new is created.
        ResponseEntity<JsonNode> reused =
                postWithKey(Map.of("kind", "VIDEO", "contentType", "video/mp4", "bytes", 9999), key);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reused.getBody().path("code").asText("")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        // The same key belongs to each user separately.
        TestApi.User other = api.register("other");
        assertThat(postWithKey(body, key, other).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Without a key every request is a new upload.
        created(api.post("/api/v1/media/uploads", body, owner));
        assertThat(dsl.fetchValue("select count(*) from media.media_asset where owner_id = ?", owner.id()))
                .isEqualTo(2L);
        assertThat(postWithKey(body, "not-a-uuid").getBody().path("code").asText(""))
                .isEqualTo("IDEMPOTENCY_KEY_INVALID");
    }

    @Test
    void someoneElsesUploadLooksLikeItDoesNotExistAndAbortRemovesAnUnfinishedOne() throws Exception {
        JsonNode ticket = createVideo(2000);
        String mediaId = ticket.path("mediaId").asText("");
        TestApi.User stranger = api.register("stranger");

        assertThat(api.get("/api/v1/media/" + mediaId, stranger).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.post("/api/v1/media/uploads/" + mediaId + "/complete", null, stranger)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.delete("/api/v1/media/uploads/" + mediaId, stranger).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/api/v1/media/" + UUID.randomUUID(), owner)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("MEDIA_NOT_FOUND");
        assertThat(api.get("/api/v1/media/" + mediaId, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // The owner aborts: the bytes (if any) are removed and the asset disappears.
        put(ticket.path("putUrl").asText(""), "video/mp4", payload(2000));
        assertThat(api.delete("/api/v1/media/uploads/" + mediaId, owner).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(storage.head("uploads/" + owner.id() + "/" + mediaId + "/original"))
                .isEmpty();
        assertThat(api.get("/api/v1/media/" + mediaId, owner).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.delete("/api/v1/media/uploads/" + mediaId, owner).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void requestsAreValidatedAndAnImageIsReadyAsSoonAsItIsComplete() throws Exception {
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("kind", "VIDEO", "contentType", "video/x-msvideo", "bytes", 10),
                Map.of("kind", "IMAGE", "contentType", "image/gif", "bytes", 10),
                Map.of("kind", "AUDIO", "contentType", "audio/mpeg", "bytes", 10),
                Map.of("kind", "VIDEO", "contentType", "video/mp4", "bytes", 10, "durationMs", 61_001),
                Map.of("kind", "VIDEO", "contentType", "video/mp4", "bytes", 0),
                Map.of("kind", "VIDEO", "contentType", "video/mp4"))) {
            assertThat(api.post("/api/v1/media/uploads", bad, owner).getStatusCode())
                    .as(bad.toString())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(api.post(
                                "/api/v1/media/uploads",
                                Map.of("kind", "IMAGE", "contentType", "image/png", "bytes", 11 * MIB),
                                owner)
                        .getStatusCode()
                        .value())
                .isEqualTo(413);

        // An avatar-sized image: no transcoding, so complete makes it READY and viewable by anyone.
        byte[] png = payload(5000);
        JsonNode ticket = created(api.post(
                "/api/v1/media/uploads",
                Map.of("kind", "IMAGE", "contentType", "image/png", "bytes", png.length),
                owner));
        String mediaId = ticket.path("mediaId").asText("");
        put(ticket.path("putUrl").asText(""), "image/png", png);
        assertThat(api.post("/api/v1/media/uploads/" + mediaId + "/complete", null, owner)
                        .getBody()
                        .path("status")
                        .asText(""))
                .isEqualTo("READY");
        JsonNode playback = ok(api.get("/api/v1/media/" + mediaId, api.register("viewer")));
        assertThat(playback.path("status").asText("")).isEqualTo("READY");
        assertThat(playback.path("posterUrl").asText("")).endsWith("/media/" + mediaId + "/v1/image.png");
        assertThat(playback.path("hlsUrl").isNull()).isTrue();
        assertThat(storage.head("media/" + mediaId + "/v1/image.png"))
                .hasValueSatisfying(head -> assertThat(head.contentLength()).isEqualTo(png.length));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private JsonNode createVideo(int bytes) {
        return created(api.post(
                "/api/v1/media/uploads", Map.of("kind", "VIDEO", "contentType", "video/mp4", "bytes", bytes), owner));
    }

    /** PUTs each part to its presigned URL and returns {@code [{partNumber, etag}]}. */
    private List<Map<String, Object>> uploadParts(JsonNode ticket, int[] sizes) throws Exception {
        List<Map<String, Object>> etags = new ArrayList<>();
        for (int i = 0; i < sizes.length; i++) {
            JsonNode part = ticket.path("parts").path(i);
            HttpResponse<Void> response = put(part.path("url").asText(""), null, payload(sizes[i]));
            assertThat(response.statusCode()).isEqualTo(200);
            etags.add(Map.of(
                    "partNumber",
                    part.path("partNumber").asInt(),
                    "etag",
                    response.headers().firstValue("ETag").orElseThrow()));
        }
        return etags;
    }

    private static HttpResponse<Void> put(String url, String contentType, byte[] bytes) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding());
    }

    private ResponseEntity<JsonNode> postWithKey(Object body, String key) {
        return postWithKey(body, key, owner);
    }

    private ResponseEntity<JsonNode> postWithKey(Object body, String key, TestApi.User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(user.accessToken());
        headers.set("Idempotency-Key", key);
        return rest.exchange("/api/v1/media/uploads", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        new java.util.Random(size).nextBytes(bytes);
        return bytes;
    }

    private static JsonNode created(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
