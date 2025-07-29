package app.soundcheck.content;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Anyone signed in can comment under any gig, before or after it; the thread reads
 * oldest first and pages; blocks hide comments both ways; a private profile's comment shows to a
 * non-follower only as "a private profile"; its author or an admin can delete it; nobody is notified.
 */
class GigCommentsIT extends IntegrationTest {

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
    Clock clock;

    TestApi api;
    TestData data;
    TestApi.User fan;
    TestApi.User mate;
    UUID gig;
    String path;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        fan = api.register("gigtalk");
        mate = api.register("gigmate");
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID act = data.artist("Keo " + tag);
        // A gig still to come: "Who's going?" is a thread too.
        gig = data.gig(
                "Keo " + tag, data.venue("Ritz " + tag), Instant.now(clock).plus(Duration.ofDays(20)), act);
        path = "/api/v1/gigs/" + gig + "/comments";
    }

    @Test
    void anyoneCanCommentUnderAGigAndTheThreadReadsOldestFirstInPages() {
        JsonNode first = created(api.post(path, Map.of("body", "  Who's going?  "), fan));
        assertThat(first.path("body").asText("")).isEqualTo("Who's going?");
        assertThat(first.path("author").path("id").asText(""))
                .isEqualTo(fan.id().toString());
        assertThat(first.path("gigId").asText("")).isEqualTo(gig.toString());
        created(api.post(path, Map.of("body", "Me, front row"), mate));
        created(api.post(path, Map.of("body", "See you there"), fan));

        assertThat(bodies(ok(api.get(path, mate)))).containsExactly("Who's going?", "Me, front row", "See you there");
        JsonNode page = ok(api.get(path + "?limit=2", mate));
        assertThat(bodies(page)).containsExactly("Who's going?", "Me, front row");
        assertThat(bodies(ok(api.get(
                        path + "?limit=2&cursor=" + page.path("nextCursor").asText(""), mate))))
                .containsExactly("See you there");

        // The same limits as a post comment; an unknown gig is not found.
        assertThat(api.post(path, Map.of("body", " "), fan).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.post(path, Map.of("body", "x".repeat(501)), fan).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(api.get("/api/v1/gigs/" + UUID.randomUUID() + "/comments", fan)))
                .isEqualTo("GIG_NOT_FOUND");
        assertThat(code(api.post("/api/v1/gigs/" + UUID.randomUUID() + "/comments", Map.of("body", "hi"), fan)))
                .isEqualTo("GIG_NOT_FOUND");

        // Nobody is told: no inbox entry for anyone.
        assertThat(ok(api.get("/api/v1/notifications", fan)).path("items").size())
                .isZero();
    }

    @Test
    void onlyItsAuthorDeletesACommentAndARetryWithTheSameKeyAddsNothing() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(fan.accessToken());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        HttpEntity<Map<String, String>> request = new HttpEntity<>(Map.of("body", "Doors at 7"), headers);
        JsonNode once =
                rest.exchange(path, HttpMethod.POST, request, JsonNode.class).getBody();
        JsonNode again =
                rest.exchange(path, HttpMethod.POST, request, JsonNode.class).getBody();
        assertThat(again.path("id").asText("")).isEqualTo(once.path("id").asText(""));
        assertThat(bodies(ok(api.get(path, mate)))).containsExactly("Doors at 7");

        String comment = "/api/v1/gig-comments/" + once.path("id").asText("");
        assertThat(code(api.delete(comment, mate))).isEqualTo("COMMENT_NOT_FOUND");
        assertThat(api.delete(comment, fan).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(bodies(ok(api.get(path, mate)))).isEmpty();
        assertThat(code(api.delete(comment, fan))).isEqualTo("COMMENT_NOT_FOUND");
    }

    @Test
    void blocksHideCommentsBothWaysAndAPrivateProfilesCommentIsOnlyForItsFollowers() {
        TestApi.User shy = api.register("shy");
        TestApi.User friend = api.register("friend");
        created(api.post(path, Map.of("body", "From the mate"), mate));
        ok(api.patch("/api/v1/me", Map.of("profileVisibility", "PRIVATE"), shy));
        created(api.post(path, Map.of("body", "Secret plans"), shy));
        api.put("/api/v1/users/" + shy.id() + "/follow", null, friend);
        assertThat(api.post("/api/v1/me/follow-requests/" + friend.id() + "/accept", null, shy)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // A non-follower sees that a private profile commented, not who or what.
        JsonNode seen = ok(api.get(path, fan)).path("items").get(1);
        assertThat(seen.path("fromPrivateProfile").asBoolean()).isTrue();
        assertThat(seen.path("author").isNull()).isTrue();
        assertThat(seen.path("body").isNull()).isTrue();
        // A follower, and the writer, see it.
        assertThat(bodies(ok(api.get(path, friend)))).containsExactly("From the mate", "Secret plans");
        assertThat(bodies(ok(api.get(path, shy)))).containsExactly("From the mate", "Secret plans");

        // A block, either way, hides the other's comments.
        created(api.post(path, Map.of("body", "From the fan"), fan));
        assertThat(bodies(ok(api.get(path, mate)))).contains("From the fan");
        assertThat(api.put("/api/v1/users/" + mate.id() + "/block", null, fan).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(bodies(ok(api.get(path, fan))))
                .doesNotContain("From the mate")
                .contains("From the fan");
        assertThat(bodies(ok(api.get(path, mate))))
                .doesNotContain("From the fan")
                .contains("From the mate");
    }

    private static List<String> bodies(JsonNode page) {
        List<String> bodies = new ArrayList<>();
        page.path("items").forEach(c -> bodies.add(c.path("body").asText("")));
        return bodies;
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static JsonNode created(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static String code(ResponseEntity<JsonNode> response) {
        return response.getBody().path("code").asText("");
    }
}
