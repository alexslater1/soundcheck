package app.soundcheck.feed;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/** The Following feed is chronological, and leaves out hidden posts, unfollowed and blocked people. */
class FollowingFeedIT extends IntegrationTest {

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

    @Test
    void theFeedShowsFollowedPeoplesPostsNewestFirstAndRespectsHideUnfollowAndBlock() {
        TestApi api = new TestApi(rest);
        TestData data = new TestData(catalogIngest, dsl, tx, events);
        TestApi.User viewer = api.register("viewer");
        TestApi.User anna = api.register("anna");
        TestApi.User ben = api.register("ben");
        TestApi.User stranger = api.register("stranger");

        assertThat(feed(api, viewer, "")).isEmpty(); // following nobody yet

        api.put("/api/v1/users/" + anna.id() + "/follow", null, viewer);
        api.put("/api/v1/users/" + ben.id() + "/follow", null, viewer);
        String a1 = post(api, data, anna, "anna 1");
        String b1 = post(api, data, ben, "ben 1");
        String a2 = post(api, data, anna, "anna 2");
        post(api, data, stranger, "not followed");
        post(api, data, viewer, "my own"); // one does not follow oneself

        assertThat(feed(api, viewer, "")).containsExactly(a2, b1, a1);
        JsonNode first = api.get("/api/v1/feed/following?limit=2", viewer).getBody();
        assertThat(ids(first.path("items"))).containsExactly(a2, b1);
        assertThat(first.path("items").get(0).path("author").path("isFollowing").asBoolean())
                .isTrue();
        assertThat(feed(
                        api,
                        viewer,
                        "?limit=2&cursor=" + first.path("nextCursor").asText("")))
                .containsExactly(a1);

        // "Show me less" removes that post from this viewer's feed only.
        api.post("/api/v1/posts/" + b1 + "/hide", null, viewer);
        assertThat(feed(api, viewer, "")).containsExactly(a2, a1);

        // Unfollowing removes the person's posts; a block does too (it removes the follow).
        api.delete("/api/v1/users/" + ben.id() + "/follow", viewer);
        api.put("/api/v1/users/" + viewer.id() + "/block", null, anna);
        assertThat(feed(api, viewer, "")).isEmpty();
    }

    /**
     * The order is by when the post went up, not by when its gig was. A
     * clip of last year's gig posted today is new to followers, so it comes first; each item carries
     * its gig's date for the app to show.
     */
    @Test
    void aClipOfAnOlderGigPostedLaterComesFirstAndCarriesItsGigsDate() {
        TestApi api = new TestApi(rest);
        TestData data = new TestData(catalogIngest, dsl, tx, events);
        TestApi.User viewer = api.register("viewer");
        TestApi.User anna = api.register("anna");
        api.put("/api/v1/users/" + anna.id() + "/follow", null, viewer);
        UUID venue = data.venue("The Lexington " + UUID.randomUUID());
        UUID recent = data.gig("Recent night", venue, Instant.parse("2026-09-26T19:30:00Z"));
        UUID older = data.gig("Older night", venue, Instant.parse("2025-03-14T19:30:00Z"));

        String fromRecentGig = postAt(api, data, anna, recent);
        String fromOlderGig = postAt(api, data, anna, older);

        JsonNode items = api.get("/api/v1/feed/following", viewer).getBody().path("items");
        assertThat(ids(items)).containsExactly(fromOlderGig, fromRecentGig);
        assertThat(items.get(0).path("gig").path("startsAt").asText("")).startsWith("2025-03-14");
        assertThat(items.get(1).path("gig").path("startsAt").asText("")).startsWith("2026-09-26");
    }

    private static String postAt(TestApi api, TestData data, TestApi.User author, UUID gig) {
        return api.post("/api/v1/posts", Map.of("mediaId", data.video(author.id(), "READY"), "gigId", gig), author)
                .getBody()
                .path("id")
                .asText("");
    }

    private static String post(TestApi api, TestData data, TestApi.User author, String caption) {
        return api.post(
                        "/api/v1/posts",
                        Map.of("mediaId", data.video(author.id(), "READY"), "caption", caption),
                        author)
                .getBody()
                .path("id")
                .asText("");
    }

    private static List<String> feed(TestApi api, TestApi.User user, String query) {
        return ids(api.get("/api/v1/feed/following" + query, user).getBody().path("items"));
    }

    private static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.path("id").asText("")));
        return ids;
    }
}
