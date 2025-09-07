package app.soundcheck.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.shared.Ids;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fans report post comments and gig comments with a reason, as they report posts. The comment
 * must be there for the reporter; a repeat is a no-op; the table still holds exactly one target per
 * report (report_one_target, V143).
 */
class ReportCommentsIT extends IntegrationTest {

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
    UUID gig;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        author = api.register("rcauthor");
        fan = api.register("rcfan");
        postId = api.post(
                        "/api/v1/posts",
                        Map.of("clips", List.of(Map.of("mediaId", data.video(author.id(), "READY")))),
                        author)
                .getBody()
                .path("id")
                .asText("");
        gig = data.gig(
                "Report night", data.venue("Report hall " + UUID.randomUUID()), Instant.parse("2026-07-01T19:30:00Z"));
    }

    @Test
    void aGigCommentIsReportedOnceWithItsReasonAndARepeatChangesNothing() {
        String comment = gigComment(author, "buy cheap tickets at example.com");

        ResponseEntity<JsonNode> first = api.post(
                "/api/v1/gig-comments/" + comment + "/report",
                Map.of("reason", "SPAM", "details", "  Ticket tout  "),
                fan);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<JsonNode> again =
                api.post("/api/v1/gig-comments/" + comment + "/report", Map.of("reason", "ABUSE"), fan);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        List<Record> rows = reportsOn("gig_comment_id", comment);
        assertThat(rows).hasSize(1);
        Record row = rows.getFirst();
        assertThat(row.get("reason")).isEqualTo("SPAM");
        assertThat(row.get("details")).isEqualTo("Ticket tout");
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("action")).isNull();
        assertThat(row.get("post_id")).isNull();
        assertThat(row.get("comment_id")).isNull();
        assertThat(row.get("reporter_id")).isEqualTo(fan.id());

        // Someone else's report is a second row: one per user and target.
        TestApi.User other = api.register("rcother");
        api.post("/api/v1/gig-comments/" + comment + "/report", Map.of("reason", "OTHER"), other);
        assertThat(reportsOn("gig_comment_id", comment)).hasSize(2);
    }

    @Test
    void aPostCommentIsReportedOnceWithItsReason() {
        String comment = postComment(author, "first!");

        assertThat(api.post("/api/v1/comments/" + comment + "/report", Map.of("reason", "ABUSE"), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.post("/api/v1/comments/" + comment + "/report", Map.of("reason", "ABUSE"), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        List<Record> rows = reportsOn("comment_id", comment);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("reason")).isEqualTo("ABUSE");
        assertThat(rows.getFirst().get("details")).isNull();
        // A comment report does not count as a report on its post.
        assertThat(reportsOn("post_id", postId)).isEmpty();
    }

    @Test
    void aCommentThatIsNotThereForTheReporterIs404AndABadReasonIs400() {
        // Deleted comments, of either kind.
        String deleted = postComment(author, "gone soon");
        api.delete("/api/v1/comments/" + deleted, author);
        String deletedGig = gigComment(author, "gone soon");
        api.delete("/api/v1/gig-comments/" + deletedGig, author);
        assertThat(code(api.post("/api/v1/comments/" + deleted + "/report", Map.of("reason", "SPAM"), fan)))
                .isEqualTo("COMMENT_NOT_FOUND");
        assertThat(code(api.post("/api/v1/gig-comments/" + deletedGig + "/report", Map.of("reason", "SPAM"), fan)))
                .isEqualTo("COMMENT_NOT_FOUND");
        // Ids that never existed.
        assertThat(code(api.post("/api/v1/comments/" + Ids.newId() + "/report", Map.of("reason", "SPAM"), fan)))
                .isEqualTo("COMMENT_NOT_FOUND");
        assertThat(code(api.post("/api/v1/gig-comments/" + Ids.newId() + "/report", Map.of("reason", "SPAM"), fan)))
                .isEqualTo("COMMENT_NOT_FOUND");

        // A block either way: the comments are not shown to the reporter, so they cannot be reported.
        String live = postComment(author, "still here");
        String liveGig = gigComment(author, "still here");
        api.put("/api/v1/users/" + fan.id() + "/block", null, author);
        assertThat(code(api.post("/api/v1/comments/" + live + "/report", Map.of("reason", "SPAM"), fan)))
                .isEqualTo("COMMENT_NOT_FOUND");
        assertThat(code(api.post("/api/v1/gig-comments/" + liveGig + "/report", Map.of("reason", "SPAM"), fan)))
                .isEqualTo("COMMENT_NOT_FOUND");
        api.delete("/api/v1/users/" + fan.id() + "/block", author);

        // A comment under a post the reporter cannot see (it is hidden by moderators, or not published).
        dsl.execute("update content.post set status = 'HIDDEN' where id = ?", UUID.fromString(postId));
        assertThat(code(api.post("/api/v1/comments/" + live + "/report", Map.of("reason", "SPAM"), fan)))
                .isEqualTo("COMMENT_NOT_FOUND");
        dsl.execute("update content.post set status = 'PUBLISHED' where id = ?", UUID.fromString(postId));

        // A reason that is not one of the six.
        ResponseEntity<JsonNode> bad =
                api.post("/api/v1/gig-comments/" + liveGig + "/report", Map.of("reason", "BORING"), fan);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().path("code").asText("")).isEqualTo("REPORT_REASON_INVALID");
        assertThat(api.post("/api/v1/comments/" + live + "/report", Map.of("details", "no reason"), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reportsOn("comment_id", live)).isEmpty();
        assertThat(reportsOn("gig_comment_id", liveGig)).isEmpty();

        // Without a token: the normal authentication applies (these are user endpoints, not admin ones).
        assertThat(api.post("/api/v1/comments/" + live + "/report", Map.of("reason", "SPAM"), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aReportNamesExactlyOneTarget() {
        UUID comment = UUID.fromString(postComment(author, "a comment"));
        UUID gigCommentId = UUID.fromString(gigComment(author, "a gig comment"));

        // Two targets, or none: refused by report_one_target.
        assertThatThrownBy(() -> dsl.execute(
                        "insert into content.report (id, reporter_id, post_id, gig_comment_id, reason)"
                                + " values (?, ?, ?, ?, 'SPAM')",
                        Ids.newId(),
                        fan.id(),
                        UUID.fromString(postId),
                        gigCommentId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("report_one_target");
        assertThatThrownBy(() -> dsl.execute(
                        "insert into content.report (id, reporter_id, comment_id, gig_comment_id, reason)"
                                + " values (?, ?, ?, ?, 'SPAM')",
                        Ids.newId(),
                        fan.id(),
                        comment,
                        gigCommentId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("report_one_target");
        assertThatThrownBy(() -> dsl.execute(
                        "insert into content.report (id, reporter_id, reason) values (?, ?, 'SPAM')",
                        Ids.newId(),
                        fan.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("report_one_target");
        // An action on an OPEN report, or none on a resolved one: refused too.
        assertThatThrownBy(() -> dsl.execute(
                        "insert into content.report (id, reporter_id, gig_comment_id, reason, action)"
                                + " values (?, ?, ?, 'SPAM', 'DISMISS')",
                        Ids.newId(),
                        fan.id(),
                        gigCommentId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("report_action_when_resolved");
    }

    private String postComment(TestApi.User who, String body) {
        ResponseEntity<JsonNode> created = api.post("/api/v1/posts/" + postId + "/comments", Map.of("body", body), who);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().path("id").asText("");
    }

    private String gigComment(TestApi.User who, String body) {
        ResponseEntity<JsonNode> created = api.post("/api/v1/gigs/" + gig + "/comments", Map.of("body", body), who);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().path("id").asText("");
    }

    private List<Record> reportsOn(String column, String id) {
        return dsl.fetch("select * from content.report where " + column + " = ?", UUID.fromString(id));
    }

    private static String code(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        return response.getBody().path("code").asText("");
    }
}
