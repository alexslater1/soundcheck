package app.soundcheck.content;

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
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The console's queue groups reports by what they report. Three reports on a post and two on a
 * comment are two items, with their counts, reasons and texts, and paging walks every item once.
 *
 * <p>The database is shared with the other tests, whose reports are in the queue too, so the tests find
 * their own targets in it rather than expecting a fixed list.
 */
class ModerationQueueIT extends IntegrationTest {

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
    String postId;
    String commentId;
    UUID gig;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        author = api.register("mqauthor");
        UUID venue = data.venue("Queue hall " + UUID.randomUUID());
        UUID act = data.artist("Queue act " + UUID.randomUUID());
        gig = data.gig("Queue night", venue, Instant.parse("2026-06-01T19:30:00Z"), act);
        postId = api.post(
                        "/api/v1/posts",
                        Map.of(
                                "clips",
                                List.of(Map.of("mediaId", data.video(author.id(), "READY"), "artistId", act)),
                                "caption",
                                "Front row",
                                "gigId",
                                gig),
                        author)
                .getBody()
                .path("id")
                .asText("");
        TestApi.User commenter = api.register("mqcommenter");
        commentId = api.post("/api/v1/posts/" + postId + "/comments", Map.of("body", "rubbish band"), commenter)
                .getBody()
                .path("id")
                .asText("");
    }

    @Test
    void reportsAreGroupedByTargetWithTheirCountsReasonsAndTexts() {
        report("posts/" + postId, Map.of("reason", "SPAM"));
        report("posts/" + postId, Map.of("reason", "SPAM", "details", "Advert"));
        report("posts/" + postId, Map.of("reason", "COPYRIGHT", "details", "My footage"));
        report("comments/" + commentId, Map.of("reason", "ABUSE", "details", "Rude"));
        report("comments/" + commentId, Map.of("reason", "ABUSE"));

        List<JsonNode> items = all("OPEN", 50);
        JsonNode post = only(items, postId);
        JsonNode comment = only(items, commentId);

        assertThat(post.path("kind").asText("")).isEqualTo("POST");
        assertThat(post.path("reportCount").asInt()).isEqualTo(3);
        assertThat(post.path("reasons").path("SPAM").asInt()).isEqualTo(2);
        assertThat(post.path("reasons").path("COPYRIGHT").asInt()).isEqualTo(1);
        assertThat(post.path("reasons").size()).isEqualTo(2);
        assertThat(texts(post)).containsExactly("My footage", "Advert"); // newest first
        assertThat(post.path("resolution").isNull()).isTrue();
        assertThat(post.path("comment").isNull()).isTrue();
        assertThat(dsl.fetchExists(dsl.selectFrom("content.report")
                        .where(
                                "id = ? and post_id = ?",
                                UUID.fromString(post.path("reportId").asText("")),
                                UUID.fromString(postId))))
                .isTrue();
        assertThat(Instant.parse(post.path("firstReportedAt").asText("")))
                .isBeforeOrEqualTo(Instant.parse(post.path("lastReportedAt").asText("")));
        JsonNode preview = post.path("post");
        assertThat(preview.path("status").asText("")).isEqualTo("PUBLISHED");
        assertThat(preview.path("caption").asText("")).isEqualTo("Front row");
        assertThat(preview.path("author").path("handle").asText("")).isEqualTo(author.handle());
        assertThat(preview.path("gig").path("title").asText("")).isEqualTo("Queue night");
        assertThat(preview.path("playback").path("status").asText("")).isEqualTo("READY");
        assertThat(preview.path("playback").path("posterUrl").asText("")).isNotBlank();
        assertThat(preview.path("clipCount").asInt()).isEqualTo(1);

        assertThat(comment.path("kind").asText("")).isEqualTo("COMMENT");
        assertThat(comment.path("reportCount").asInt()).isEqualTo(2);
        assertThat(comment.path("reasons").path("ABUSE").asInt()).isEqualTo(2);
        assertThat(texts(comment)).containsExactly("Rude");
        assertThat(comment.path("comment").path("body").asText("")).isEqualTo("rubbish band");
        assertThat(comment.path("comment").path("deleted").asBoolean()).isFalse();
        assertThat(comment.path("comment").path("post").path("caption").asText(""))
                .isEqualTo("Front row");
        assertThat(comment.path("post").isNull()).isTrue();

        // A gig comment shows the gig it was written under.
        String gigComment = api.post("/api/v1/gigs/" + gig + "/comments", Map.of("body", "who's going?"), author)
                .getBody()
                .path("id")
                .asText("");
        report("gig-comments/" + gigComment, Map.of("reason", "OTHER"));
        JsonNode onGig = only(all("OPEN", 50), gigComment);
        assertThat(onGig.path("kind").asText("")).isEqualTo("GIG_COMMENT");
        assertThat(onGig.path("comment").path("gig").path("title").asText("")).isEqualTo("Queue night");
        assertThat(onGig.path("comment").path("post").isNull()).isTrue();

        // Nothing resolved yet: none of them is on the Resolved tab.
        List<JsonNode> resolved = all("RESOLVED", 50);
        assertThat(resolved.stream().map(i -> i.path("id").asText(""))).doesNotContain(postId, commentId, gigComment);
    }

    @Test
    void pagingWalksEveryTargetOnceOldestFirst() {
        List<String> mine = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String id = api.post(
                            "/api/v1/posts",
                            Map.of("clips", List.of(Map.of("mediaId", data.video(author.id(), "READY")))),
                            author)
                    .getBody()
                    .path("id")
                    .asText("");
            report("posts/" + id, Map.of("reason", "SPAM"));
            report("posts/" + id, Map.of("reason", "OTHER"));
            mine.add(id);
        }

        List<JsonNode> pages = all("OPEN", 1);
        List<String> ids = pages.stream().map(i -> i.path("id").asText("")).toList();
        List<Instant> firsts = pages.stream()
                .map(i -> Instant.parse(i.path("firstReportedAt").asText("")))
                .toList();

        assertThat(ids).doesNotHaveDuplicates().containsAll(mine);
        assertThat(firsts).isSorted();
        // The same walk in bigger pages finds the same items in the same order.
        assertThat(all("OPEN", 3).stream().map(i -> i.path("id").asText("")).toList())
                .containsExactlyElementsOf(ids);
        for (String id : mine) {
            assertThat(only(pages, id).path("reportCount").asInt()).isEqualTo(2);
        }
    }

    @Test
    void theQueueIsForAdminsOnly() {
        TestApi.User fan = api.register("mqfan");
        assertThat(api.get("/api/v1/admin/reports", fan).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get("/api/v1/admin/reports?status=SOMETIMES", api.admin())
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Every item of a tab, page by page. */
    private List<JsonNode> all(String status, int limit) {
        List<JsonNode> items = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ResponseEntity<JsonNode> response = api.get(
                    "/api/v1/admin/reports?status=" + status + "&limit=" + limit
                            + (cursor == null ? "" : "&cursor=" + cursor),
                    api.admin());
            assertThat(response.getStatusCode())
                    .as(String.valueOf(response.getBody()))
                    .isEqualTo(HttpStatus.OK);
            response.getBody().path("items").forEach(items::add);
            JsonNode next = response.getBody().path("nextCursor");
            cursor = next.isNull() ? null : next.asText("");
            pages++;
        } while (cursor != null && pages < 2000);
        assertThat(cursor).as("the walk ends").isNull();
        return items;
    }

    private void report(String target, Map<String, String> body) {
        TestApi.User reporter = api.register("mqreporter");
        assertThat(api.post("/api/v1/" + target + "/report", body, reporter).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private static JsonNode only(List<JsonNode> items, String id) {
        List<JsonNode> matching =
                items.stream().filter(i -> i.path("id").asText("").equals(id)).toList();
        assertThat(matching).as("one item for " + id).hasSize(1);
        return matching.getFirst();
    }

    private static List<String> texts(JsonNode item) {
        List<String> texts = new ArrayList<>();
        item.path("latestDetails").forEach(t -> texts.add(t.asText("")));
        return texts;
    }
}
