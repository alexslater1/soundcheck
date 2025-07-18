package app.soundcheck.content;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.Parallel;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
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
 * Likes are idempotent and the counter is exact under concurrency (50 distinct users
 * at once → 50; one user ten times → 1); comments with their permissions; hide and report.
 */
class EngagementApiIT extends IntegrationTest {

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
    TestApi.User fan;
    String postId;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        author = api.register("author");
        fan = api.register("fan");
        postId = api.post(
                        "/api/v1/posts",
                        Map.of("clips", List.of(Map.of("mediaId", data.video(author.id(), "READY")))),
                        author)
                .getBody()
                .path("id")
                .asText("");
    }

    @Test
    void fiftyUsersLikingAtOnceCountFiftyAndOneUserLikingTenTimesCountsOnce() throws Exception {
        List<TestApi.User> users = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            users.add(api.register("liker"));
        }

        List<HttpStatus> statuses = Parallel.run(50, i -> (HttpStatus)
                api.put("/api/v1/posts/" + postId + "/like", null, users.get(i)).getStatusCode());

        assertThat(statuses).containsOnly(HttpStatus.OK);
        assertThat(likeCount()).isEqualTo(50);
        assertThat(likeRows()).isEqualTo(50);

        // The same user, ten times at once: one row, one increment.
        Parallel.run(
                10, i -> api.put("/api/v1/posts/" + postId + "/like", null, fan).getStatusCode());
        assertThat(likeCount()).isEqualTo(51);
        assertThat(likeRows()).isEqualTo(51);

        // Unliking ten times at once removes it once.
        Parallel.run(
                10, i -> api.delete("/api/v1/posts/" + postId + "/like", fan).getStatusCode());
        assertThat(likeCount()).isEqualTo(50);
        assertThat(likeRows()).isEqualTo(50);
    }

    @Test
    void aLikeShowsOnThePostForTheLikerOnlyAndBothDirectionsAreIdempotent() {
        JsonNode liked = api.put("/api/v1/posts/" + postId + "/like", null, fan).getBody();
        assertThat(liked.path("likeCount").asInt()).isEqualTo(1);
        assertThat(liked.path("likedByMe").asBoolean()).isTrue();
        assertThat(api.put("/api/v1/posts/" + postId + "/like", null, fan)
                        .getBody()
                        .path("likeCount")
                        .asInt())
                .isEqualTo(1);

        assertThat(api.get("/api/v1/posts/" + postId, fan)
                        .getBody()
                        .path("likedByMe")
                        .asBoolean())
                .isTrue();
        JsonNode forAuthor = api.get("/api/v1/posts/" + postId, author).getBody();
        assertThat(forAuthor.path("likedByMe").asBoolean()).isFalse();
        assertThat(forAuthor.path("likeCount").asInt()).isEqualTo(1);

        JsonNode unliked = api.delete("/api/v1/posts/" + postId + "/like", fan).getBody();
        assertThat(unliked.path("likeCount").asInt()).isZero();
        assertThat(unliked.path("likedByMe").asBoolean()).isFalse();
        assertThat(api.delete("/api/v1/posts/" + postId + "/like", fan)
                        .getBody()
                        .path("likeCount")
                        .asInt())
                .isZero();

        // A post that is not published cannot be engaged with: it "does not exist".
        String pending = api.post(
                        "/api/v1/posts",
                        Map.of("clips", List.of(Map.of("mediaId", data.video(author.id(), "UPLOADED")))),
                        author)
                .getBody()
                .path("id")
                .asText("");
        for (ResponseEntity<JsonNode> response : List.of(
                api.put("/api/v1/posts/" + pending + "/like", null, fan),
                api.post("/api/v1/posts/" + pending + "/comments", Map.of("body", "hello"), fan),
                api.get("/api/v1/posts/" + pending + "/comments", fan),
                api.post("/api/v1/posts/" + pending + "/hide", null, fan),
                api.post("/api/v1/posts/" + pending + "/report", Map.of("reason", "SPAM"), fan))) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void commentsAreListedOldestFirstCountedAndDeletableByTheirAuthorOrThePostsAuthor() {
        TestApi.User stranger = api.register("stranger");
        List<String> ids = new ArrayList<>();
        for (String body : List.of("first", "  second  ", "third")) {
            ResponseEntity<JsonNode> created =
                    api.post("/api/v1/posts/" + postId + "/comments", Map.of("body", body), fan);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            ids.add(created.getBody().path("id").asText(""));
        }
        ids.add(api.post("/api/v1/posts/" + postId + "/comments", Map.of("body", "from a stranger"), stranger)
                .getBody()
                .path("id")
                .asText(""));

        JsonNode page =
                api.get("/api/v1/posts/" + postId + "/comments?limit=3", author).getBody();
        assertThat(page.path("items"))
                .extracting(c -> c.path("body").asText(""))
                .containsExactly("first", "second", "third");
        assertThat(page.path("items").get(0).path("author").path("handle").asText(""))
                .isEqualTo(fan.handle());
        JsonNode rest2 = api.get(
                        "/api/v1/posts/" + postId + "/comments?limit=3&cursor="
                                + page.path("nextCursor").asText(""),
                        author)
                .getBody();
        assertThat(rest2.path("items"))
                .extracting(c -> c.path("body").asText(""))
                .containsExactly("from a stranger");
        assertThat(rest2.path("nextCursor").isNull()).isTrue();
        assertThat(commentCount()).isEqualTo(4);

        // Validation: empty and over-long bodies.
        assertThat(api.post("/api/v1/posts/" + postId + "/comments", Map.of("body", "   "), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.post("/api/v1/posts/" + postId + "/comments", Map.of("body", "x".repeat(501)), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // A stranger cannot delete someone else's comment; its author can; the post's author can delete any.
        assertThat(api.delete("/api/v1/comments/" + ids.get(0), stranger).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.delete("/api/v1/comments/" + ids.get(0), fan).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.delete("/api/v1/comments/" + ids.get(3), author).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.delete("/api/v1/comments/" + ids.get(3), author).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND); // already gone
        assertThat(commentCount()).isEqualTo(2);
        assertThat(api.get("/api/v1/posts/" + postId + "/comments", author)
                        .getBody()
                        .path("items"))
                .extracting(c -> c.path("body").asText(""))
                .containsExactly("second", "third");
        assertThat(api.get("/api/v1/posts/" + postId, fan)
                        .getBody()
                        .path("commentCount")
                        .asInt())
                .isEqualTo(2);
    }

    @Test
    void aRetriedCommentWithTheSameKeyIsNotPostedTwice() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = commentWithKey("Great set!", key);
        ResponseEntity<JsonNode> retry = commentWithKey("Great set!", key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody().path("id").asText(""))
                .isEqualTo(first.getBody().path("id").asText(""));
        assertThat(commentCount()).isEqualTo(1);
        assertThat(commentWithKey("Something else", key).getBody().path("code").asText(""))
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void hidingAndReportingAreRecordedOncePerUser() {
        assertThat(api.post("/api/v1/posts/" + postId + "/hide", null, fan).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.post("/api/v1/posts/" + postId + "/hide", null, fan).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count("content.post_hide", "post_id = ? and user_id = ?", fan.id()))
                .isEqualTo(1);

        assertThat(api.post(
                                "/api/v1/posts/" + postId + "/report",
                                Map.of("reason", "COPYRIGHT", "details", "  Not their footage  "),
                                fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.post("/api/v1/posts/" + postId + "/report", Map.of("reason", "SPAM"), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        var report = dsl.fetchSingle(
                "select reason, details, status from content.report where post_id = ? and reporter_id = ?",
                UUID.fromString(postId),
                fan.id());
        // The first report stands; a repeat does not add a second row or change it.
        assertThat(report.get("reason")).isEqualTo("COPYRIGHT");
        assertThat(report.get("details")).isEqualTo("Not their footage");
        assertThat(report.get("status")).isEqualTo("OPEN");

        assertThat(api.post("/api/v1/posts/" + postId + "/report", Map.of("reason", "BORING"), fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("REPORT_REASON_INVALID");
        assertThat(api.post("/api/v1/posts/" + postId + "/report", Map.of("details", "no reason"), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private ResponseEntity<JsonNode> commentWithKey(String body, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(fan.accessToken());
        headers.set("X-Client", "android");
        headers.set("Idempotency-Key", key);
        return rest.exchange(
                "/api/v1/posts/" + postId + "/comments",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("body", body), headers),
                JsonNode.class);
    }

    private int likeCount() {
        return dsl.fetchSingle("select like_count from content.post where id = ?", UUID.fromString(postId))
                .get(0, Integer.class);
    }

    private int commentCount() {
        return dsl.fetchSingle("select comment_count from content.post where id = ?", UUID.fromString(postId))
                .get(0, Integer.class);
    }

    private int likeRows() {
        return dsl.fetchCount(dsl.selectFrom("content.post_like").where("post_id = ?", UUID.fromString(postId)));
    }

    private int count(String table, String where, UUID userId) {
        return dsl.fetchCount(dsl.selectFrom(table).where(where, UUID.fromString(postId), userId));
    }
}
