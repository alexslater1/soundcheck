package app.soundcheck.content;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.shared.Ids;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Hidden means gone everywhere": after a moderator hides a post it is gone from For
 * You (also from the next page of a session built before the hide), Following, another user's view of the
 * profile, the gig, act and venue lists, the single GET for anyone but its author, and notification
 * briefs; its author still sees it, marked HIDDEN. Restore brings all of it back, unless the author has
 * posted again for the same gig and day. A decision resolves every OPEN report on its target, and an
 * action that does not fit the target is refused.
 */
class ModerationIT extends IntegrationTest {

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
    PostLookup lookup;

    @Autowired
    JsonMapper json;

    TestApi api;
    TestData data;
    TestApi.User viewer;
    UUID venue;
    UUID act;
    UUID gig;
    /** Six authors, each with one post of the act at the gig; the viewer follows them all. */
    List<TestApi.User> authors;

    List<String> posts;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        viewer = api.register("modviewer");
        venue = data.venue("Moderation hall " + UUID.randomUUID());
        act = data.artist("Moderated act " + UUID.randomUUID());
        gig = data.gig("Moderation night", venue, Instant.parse("2026-03-07T19:30:00Z"), act);
        authors = new ArrayList<>();
        posts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            TestApi.User author = api.register("modauthor");
            api.put("/api/v1/users/" + author.id() + "/follow", null, viewer);
            authors.add(author);
            posts.add(postAtGig(author));
        }
    }

    @Test
    void aHiddenPostIsGoneEverywhereButForItsAuthorAndRestoreBringsItBack() {
        // A For You session built before the hide; its first page is served.
        JsonNode first = home("?limit=1");
        String sessionId = first.path("sessionId").asText("");
        List<String> stored = sessionPostIds(sessionId);
        // The post to hide: the first of this test's posts that the session holds after the first page.
        String doomed = stored.subList(1, stored.size()).stream()
                .filter(posts::contains)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the session holds this test's posts after page one"));
        TestApi.User author = authors.get(posts.indexOf(doomed));
        TestApi.User stranger = api.register("modstranger");
        assertThat(attendance(author)).isEqualTo("WENT");

        // Three fans report it; the moderator hides it with one decision.
        for (String reason : List.of("NUDITY", "NUDITY", "VIOLENCE")) {
            report("posts/" + doomed, reason);
        }
        JsonNode resolved = resolve(reportOn("post_id", doomed), "HIDE_POST");
        assertThat(resolved.path("resolvedReports").asInt()).isEqualTo(3);
        assertThat(resolved.path("kind").asText("")).isEqualTo("POST");
        List<Record> reports = dsl.fetch(
                "select status, action, resolved_by, resolved_at from content.report where post_id = ?",
                UUID.fromString(doomed));
        assertThat(reports).hasSize(3).allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("ACTIONED");
            assertThat(r.get("action")).isEqualTo("HIDE_POST");
            assertThat(r.get("resolved_by")).isEqualTo(api.admin().id());
            assertThat(r.get("resolved_at")).isNotNull();
        });

        // For You: the rest of the session built before the hide, and a new session.
        List<String> rest = new ArrayList<>();
        String cursor = first.path("nextCursor").asText("");
        for (int pages = 0; cursor != null && pages < 500; pages++) {
            JsonNode page = home("?limit=10&cursor=" + cursor);
            page.path("items")
                    .forEach(item -> rest.add(item.path("post").path("id").asText("")));
            cursor = page.path("nextCursor").isNull()
                    ? null
                    : page.path("nextCursor").asText("");
        }
        assertThat(rest).doesNotContain(doomed).isNotEmpty();
        assertThat(sessionPostIds(home("?limit=1").path("sessionId").asText("")))
                .doesNotContain(doomed);
        // Following, the author's profile seen by someone else, and the gig, act and venue lists.
        List<String> others = posts.stream().filter(p -> !p.equals(doomed)).toList();
        assertThat(ids(api.get("/api/v1/feed/following?limit=50", viewer)))
                .doesNotContain(doomed)
                .containsAll(others);
        assertThat(ids(api.get("/api/v1/users/" + author.id() + "/posts", stranger)))
                .isEmpty();
        for (String list : List.of("/gigs/" + gig, "/artists/" + act, "/venues/" + venue)) {
            assertThat(ids(api.get("/api/v1" + list + "/posts?limit=50", stranger)))
                    .as(list)
                    .doesNotContain(doomed)
                    .containsAll(others);
        }
        // The single GET: 404 to anyone else, HIDDEN to its author (and on their own grid).
        for (TestApi.User someone : List.of(viewer, stranger)) {
            ResponseEntity<JsonNode> response = api.get("/api/v1/posts/" + doomed, someone);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().path("code").asText("")).isEqualTo("POST_NOT_FOUND");
        }
        assertThat(api.get("/api/v1/posts/" + doomed, author)
                        .getBody()
                        .path("status")
                        .asText(""))
                .isEqualTo("HIDDEN");
        JsonNode mine = api.get("/api/v1/me/posts", author).getBody().path("items");
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).path("status").asText("")).isEqualTo("HIDDEN");
        // Notification briefs (thumbnails in inboxes).
        assertThat(lookup.briefs(List.of(UUID.fromString(doomed)))).isEmpty();
        // Engaging with it is 404 as for any post one cannot see.
        assertThat(api.put("/api/v1/posts/" + doomed + "/like", null, viewer).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // The author's Went stays: a hidden post still exists.
        assertThat(attendance(author)).isEqualTo("WENT");

        // Restore: everything comes back, and the history shows the post's status now.
        assertThat(api.post("/api/v1/admin/posts/" + doomed + "/restore", null, api.admin())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.get("/api/v1/posts/" + doomed, viewer).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(api.get("/api/v1/feed/following?limit=50", viewer))).contains(doomed);
        assertThat(ids(api.get("/api/v1/users/" + author.id() + "/posts", stranger)))
                .containsExactly(doomed);
        for (String list : List.of("/gigs/" + gig, "/artists/" + act, "/venues/" + venue)) {
            assertThat(ids(api.get("/api/v1" + list + "/posts?limit=50", stranger)))
                    .as(list)
                    .contains(doomed);
        }
        assertThat(lookup.briefs(List.of(UUID.fromString(doomed)))).containsKey(UUID.fromString(doomed));
        assertThat(sessionPostIds(home("?limit=1").path("sessionId").asText("")))
                .contains(doomed);
        assertThat(dsl.fetchCount(dsl.selectFrom("content.report")
                        .where("post_id = ? and status = 'ACTIONED'", UUID.fromString(doomed))))
                .as("the reports stay resolved")
                .isEqualTo(3);
        JsonNode history = resolvedItem(doomed);
        assertThat(history.path("resolution").path("action").asText("")).isEqualTo("HIDE_POST");
        assertThat(history.path("resolution").path("targetStatus").asText("")).isEqualTo("PUBLISHED");
        assertThat(history.path("resolution").path("resolvedBy").asText(""))
                .isEqualTo(api.admin().handle());
        assertThat(history.path("reasons").path("NUDITY").asInt()).isEqualTo(2);
    }

    @Test
    void restoreIsRefusedWhenTheAuthorHasPostedAgainForTheGig() {
        TestApi.User author = authors.getFirst();
        String hidden = posts.getFirst();
        report("posts/" + hidden, "SPAM");
        resolve(reportOn("post_id", hidden), "HIDE_POST");

        // While it is hidden the author posts again from the same gig and day: a new post.
        String again = postAtGig(author);
        assertThat(again).isNotEqualTo(hidden);

        ResponseEntity<JsonNode> refused = api.post("/api/v1/admin/posts/" + hidden + "/restore", null, api.admin());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().path("code").asText("")).isEqualTo("POST_REPLACED");
        assertThat(refused.getBody().path("postId").asText("")).isEqualTo(again);
        assertThat(status(hidden)).isEqualTo("HIDDEN");

        // Once the new post is gone, the old one can come back.
        api.delete("/api/v1/posts/" + again, author);
        assertThat(api.post("/api/v1/admin/posts/" + hidden + "/restore", null, api.admin())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(status(hidden)).isEqualTo("PUBLISHED");
        // Restoring what is not hidden is refused.
        ResponseEntity<JsonNode> twice = api.post("/api/v1/admin/posts/" + hidden + "/restore", null, api.admin());
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(twice.getBody().path("code").asText("")).isEqualTo("POST_NOT_HIDDEN");
        assertThat(api.post("/api/v1/admin/posts/" + Ids.newId() + "/restore", null, api.admin())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anActionThatDoesNotFitIsRefusedAndAResolvedTargetIsNoLongerOpen() {
        String post = posts.getFirst();
        String comment = api.post("/api/v1/posts/" + post + "/comments", Map.of("body", "meh"), viewer)
                .getBody()
                .path("id")
                .asText("");
        report("posts/" + post, "SPAM");
        report("comments/" + comment, "ABUSE");
        UUID onPost = reportOn("post_id", post);
        UUID onComment = reportOn("comment_id", comment);

        assertThat(problem(api.post(
                        "/api/v1/admin/reports/" + onPost + "/resolve",
                        Map.of("action", "DELETE_COMMENT"),
                        api.admin())))
                .isEqualTo("409 ACTION_DOES_NOT_FIT");
        assertThat(problem(api.post(
                        "/api/v1/admin/reports/" + onComment + "/resolve", Map.of("action", "HIDE_POST"), api.admin())))
                .isEqualTo("409 ACTION_DOES_NOT_FIT");
        assertThat(problem(
                        api.post("/api/v1/admin/reports/" + onPost + "/resolve", Map.of("action", "BAN"), api.admin())))
                .isEqualTo("400 ACTION_INVALID");
        assertThat(problem(api.post(
                        "/api/v1/admin/reports/" + Ids.newId() + "/resolve", Map.of("action", "DISMISS"), api.admin())))
                .isEqualTo("404 REPORT_NOT_FOUND");
        assertThat(status(post)).isEqualTo("PUBLISHED");

        // DISMISS changes only the reports.
        assertThat(resolve(onPost, "DISMISS").path("resolvedReports").asInt()).isEqualTo(1);
        assertThat(status(post)).isEqualTo("PUBLISHED");
        assertThat(dsl.fetchSingle("select status, action from content.report where id = ?", onPost)
                        .intoMap())
                .containsEntry("status", "DISMISSED")
                .containsEntry("action", "DISMISS");
        // Nothing OPEN is left on the post: deciding again is refused.
        assertThat(problem(api.post(
                        "/api/v1/admin/reports/" + onPost + "/resolve", Map.of("action", "HIDE_POST"), api.admin())))
                .isEqualTo("409 REPORT_NOT_OPEN");
        assertThat(status(post)).isEqualTo("PUBLISHED");
        // A fan cannot decide anything.
        assertThat(api.post("/api/v1/admin/reports/" + onComment + "/resolve", Map.of("action", "DISMISS"), viewer)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void deleteCommentDeletesAPostCommentOrAGigCommentAsAnAdminDeleteWould() {
        String post = posts.getFirst();
        TestApi.User rude = api.register("modrude");
        String comment = api.post("/api/v1/posts/" + post + "/comments", Map.of("body", "rude words"), rude)
                .getBody()
                .path("id")
                .asText("");
        String gigComment = api.post("/api/v1/gigs/" + gig + "/comments", Map.of("body", "ruder words"), rude)
                .getBody()
                .path("id")
                .asText("");
        report("comments/" + comment, "ABUSE");
        report("comments/" + comment, "ABUSE");
        report("gig-comments/" + gigComment, "ABUSE");
        assertThat(commentCount(post)).isEqualTo(1);

        assertThat(resolve(reportOn("comment_id", comment), "DELETE_COMMENT")
                        .path("resolvedReports")
                        .asInt())
                .isEqualTo(2);
        assertThat(resolve(reportOn("gig_comment_id", gigComment), "DELETE_COMMENT")
                        .path("kind")
                        .asText(""))
                .isEqualTo("GIG_COMMENT");

        assertThat(api.get("/api/v1/posts/" + post + "/comments", viewer)
                        .getBody()
                        .path("items")
                        .size())
                .isZero();
        assertThat(commentCount(post)).isZero();
        assertThat(api.get("/api/v1/gigs/" + gig + "/comments", viewer)
                        .getBody()
                        .path("items")
                        .size())
                .isZero();
        assertThat(resolvedItem(comment).path("resolution").path("targetStatus").asText(""))
                .isEqualTo("DELETED");
        assertThat(resolvedItem(gigComment).path("comment").path("deleted").asBoolean())
                .isTrue();

        // A comment its author deleted before the decision: the decision still resolves the reports.
        String selfDeleted = api.post("/api/v1/posts/" + post + "/comments", Map.of("body", "oops"), rude)
                .getBody()
                .path("id")
                .asText("");
        report("comments/" + selfDeleted, "SPAM");
        api.delete("/api/v1/comments/" + selfDeleted, rude);
        assertThat(resolve(reportOn("comment_id", selfDeleted), "DELETE_COMMENT")
                        .path("resolvedReports")
                        .asInt())
                .isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String postAtGig(TestApi.User author) {
        ResponseEntity<JsonNode> created = api.post(
                "/api/v1/posts",
                Map.of(
                        "clips",
                        List.of(Map.of("mediaId", data.video(author.id(), "READY"), "artistId", act)),
                        "gigId",
                        gig),
                author);
        assertThat(created.getStatusCode())
                .as(String.valueOf(created.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        return created.getBody().path("id").asText("");
    }

    private void report(String target, String reason) {
        TestApi.User reporter = api.register("modreporter");
        assertThat(api.post("/api/v1/" + target + "/report", Map.of("reason", reason), reporter)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** One report on the target: the handle a decision takes. */
    private UUID reportOn(String column, String id) {
        return dsl.fetchSingle(
                        "select id from content.report where " + column + " = ? order by created_at limit 1",
                        UUID.fromString(id))
                .get(0, UUID.class);
    }

    private JsonNode resolve(UUID reportId, String action) {
        ResponseEntity<JsonNode> response =
                api.post("/api/v1/admin/reports/" + reportId + "/resolve", Map.of("action", action), api.admin());
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** The target's item on the Resolved tab (the latest decisions come first, so it is near the top). */
    private JsonNode resolvedItem(String targetId) {
        String cursor = null;
        for (int pages = 0; pages < 200; pages++) {
            JsonNode page = api.get(
                            "/api/v1/admin/reports?status=RESOLVED&limit=50"
                                    + (cursor == null ? "" : "&cursor=" + cursor),
                            api.admin())
                    .getBody();
            for (JsonNode item : page.path("items")) {
                if (item.path("id").asText("").equals(targetId)) {
                    return item;
                }
            }
            if (page.path("nextCursor").isNull()) {
                break;
            }
            cursor = page.path("nextCursor").asText("");
        }
        throw new AssertionError("no resolved item for " + targetId);
    }

    private JsonNode home(String query) {
        ResponseEntity<JsonNode> response = api.get("/api/v1/feed/home" + query, viewer);
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<String> sessionPostIds(String sessionId) {
        List<String> ids = new ArrayList<>();
        try {
            json.readTree(dsl.fetchSingle(
                                    "select items::text from feed.feed_session where id = ?",
                                    UUID.fromString(sessionId))
                            .get(0, String.class))
                    .forEach(item -> ids.add(item.path("p").asText("")));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        return ids;
    }

    private static List<String> ids(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        List<String> ids = new ArrayList<>();
        response.getBody().path("items").forEach(item -> ids.add(item.path("id").asText("")));
        return ids;
    }

    private String attendance(TestApi.User user) {
        return api.get("/api/v1/gigs/" + gig, user)
                .getBody()
                .path("gig")
                .path("myAttendance")
                .asText("");
    }

    private String status(String postId) {
        return dsl.fetchSingle("select status from content.post where id = ?", UUID.fromString(postId))
                .get(0, String.class);
    }

    private int commentCount(String postId) {
        return dsl.fetchSingle("select comment_count from content.post where id = ?", UUID.fromString(postId))
                .get(0, Integer.class);
    }

    private static String problem(ResponseEntity<JsonNode> response) {
        return response.getStatusCode().value() + " "
                + response.getBody().path("code").asText("");
    }
}
