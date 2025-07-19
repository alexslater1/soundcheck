package app.soundcheck.social;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.support.IntegrationTest;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Follows and their lists and counts; a block removes follows both ways and is
 * enforced in reads (profile, posts, comments) and writes (follow, like, comment).
 */
class SocialGraphApiIT extends IntegrationTest {

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
    TestApi.User alice;
    TestApi.User bob;
    TestApi.User carol;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        alice = api.register("alice");
        bob = api.register("bob");
        carol = api.register("carol");
    }

    @Test
    void followingIsIdempotentAndShowsInCountsListsAndNextToPosts() {
        assertThat(follow(bob, alice)).isEqualTo("FOLLOWING");
        assertThat(follow(bob, alice)).isEqualTo("FOLLOWING"); // again: nothing changes
        assertThat(follow(carol, alice)).isEqualTo("FOLLOWING");
        assertThat(follow(alice, carol)).isEqualTo("FOLLOWING");
        assertThat(dsl.fetchCount(dsl.selectFrom("social.user_follow").where("followee_id = ?", alice.id())))
                .isEqualTo(2);

        // Counts on the profile and on /me; isFollowing is the viewer's own relation.
        JsonNode asBob = profile(alice, bob);
        assertThat(asBob.path("counts").path("followers").asInt()).isEqualTo(2);
        assertThat(asBob.path("counts").path("following").asInt()).isEqualTo(1);
        assertThat(asBob.path("isFollowing").asBoolean()).isTrue();
        assertThat(asBob.path("isBlocked").asBoolean()).isFalse();
        assertThat(profile(bob, alice).path("isFollowing").asBoolean()).isFalse();
        assertThat(api.get("/api/v1/me", alice)
                        .getBody()
                        .path("counts")
                        .path("followers")
                        .asInt())
                .isEqualTo(2);

        // Followers, newest first; each entry says whether the *viewer* follows that person.
        JsonNode followers =
                api.get("/api/v1/users/" + alice.id() + "/followers", alice).getBody();
        assertThat(handles(followers.path("items"))).containsExactly(carol.handle(), bob.handle());
        assertThat(followers.path("items").get(0).path("isFollowing").asBoolean())
                .isTrue(); // alice follows carol
        assertThat(followers.path("items").get(1).path("isFollowing").asBoolean())
                .isFalse(); // but not bob
        JsonNode firstPage = api.get("/api/v1/users/" + alice.id() + "/followers?limit=1", bob)
                .getBody();
        assertThat(handles(firstPage.path("items"))).containsExactly(carol.handle());
        JsonNode secondPage = api.get(
                        "/api/v1/users/" + alice.id() + "/followers?limit=1&cursor="
                                + firstPage.path("nextCursor").asText(""),
                        bob)
                .getBody();
        assertThat(handles(secondPage.path("items"))).containsExactly(bob.handle());
        assertThat(secondPage.path("nextCursor").isNull()).isTrue();
        assertThat(handles(api.get("/api/v1/users/" + alice.id() + "/following", bob)
                        .getBody()
                        .path("items")))
                .containsExactly(carol.handle());

        // The post count and the author's isFollowing on a post.
        String postId = post(alice);
        assertThat(profile(alice, bob).path("counts").path("posts").asInt()).isEqualTo(1);
        assertThat(api.get("/api/v1/posts/" + postId, bob)
                        .getBody()
                        .path("author")
                        .path("isFollowing")
                        .asBoolean())
                .isTrue();

        // Unfollow, twice.
        assertThat(api.delete("/api/v1/users/" + alice.id() + "/follow", bob).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.delete("/api/v1/users/" + alice.id() + "/follow", bob).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(profile(alice, bob).path("counts").path("followers").asInt()).isEqualTo(1);
        assertThat(api.get("/api/v1/posts/" + postId, bob)
                        .getBody()
                        .path("author")
                        .path("isFollowing")
                        .asBoolean())
                .isFalse();

        // Not possible: yourself, or someone who does not exist.
        assertThat(api.put("/api/v1/users/" + bob.id() + "/follow", null, bob)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("CANNOT_FOLLOW_SELF");
        assertThat(api.put("/api/v1/users/" + UUID.randomUUID() + "/follow", null, bob)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aBlockRemovesFollowsBothWaysAndHidesTheTwoFromEachOther() {
        follow(bob, alice);
        follow(alice, bob);
        String alicePost = post(alice);
        String bobPost = post(bob);
        String carolPost = post(carol);
        comment(bob, carolPost, "bob was here");
        comment(alice, carolPost, "alice was here");

        assertThat(api.put("/api/v1/users/" + bob.id() + "/block", null, alice).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.put("/api/v1/users/" + bob.id() + "/block", null, alice).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT); // idempotent

        // Follows are gone in both directions.
        assertThat(dsl.fetchCount(dsl.selectFrom("social.user_follow")
                        .where(
                                "follower_id in (?, ?) and followee_id in (?, ?)",
                                alice.id(),
                                bob.id(),
                                alice.id(),
                                bob.id())))
                .isZero();

        // Profile visibility: to bob, alice is not there; alice sees bob's profile marked as blocked.
        assertThat(api.get("/api/v1/users/" + alice.handle(), bob).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(profile(bob, alice).path("isBlocked").asBoolean()).isTrue();
        assertThat(profile(alice, carol).path("isBlocked").asBoolean()).isFalse(); // nobody else is affected

        // Follow attempts: bob cannot find alice; alice must unblock first.
        assertThat(api.put("/api/v1/users/" + alice.id() + "/follow", null, bob).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<JsonNode> blockerFollows = api.put("/api/v1/users/" + bob.id() + "/follow", null, alice);
        assertThat(blockerFollows.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blockerFollows.getBody().path("code").asText("")).isEqualTo("USER_BLOCKED");

        // Writes on the blocker's post: like and comment are refused as "no such post" (and nothing is stored).
        assertThat(api.put("/api/v1/posts/" + alicePost + "/like", null, bob).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.post("/api/v1/posts/" + alicePost + "/comments", Map.of("body", "hi"), bob)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        var counters = dsl.fetchSingle(
                "select like_count, comment_count from content.post where id = ?", UUID.fromString(alicePost));
        assertThat(counters.get("like_count")).isEqualTo(0);
        assertThat(counters.get("comment_count")).isEqualTo(0);
        // …and the other way round: the blocker cannot engage with the blocked person's post either.
        assertThat(api.put("/api/v1/posts/" + bobPost + "/like", null, alice).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Reads: each other's posts and post lists are gone; a third person's are untouched.
        assertThat(api.get("/api/v1/posts/" + alicePost, bob).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/api/v1/posts/" + bobPost, alice).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/api/v1/users/" + alice.id() + "/posts", bob).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/api/v1/users/" + bob.id() + "/posts", alice)
                        .getBody()
                        .path("items")
                        .size())
                .isZero();
        assertThat(api.get("/api/v1/posts/" + alicePost, carol).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.get("/api/v1/users/" + alice.id() + "/followers", bob).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Comments on a third person's post: each does not see the other's; carol sees both.
        assertThat(bodies(api.get("/api/v1/posts/" + carolPost + "/comments", alice)
                        .getBody()
                        .path("items")))
                .containsExactly("alice was here");
        assertThat(bodies(api.get("/api/v1/posts/" + carolPost + "/comments", bob)
                        .getBody()
                        .path("items")))
                .containsExactly("bob was here");
        assertThat(bodies(api.get("/api/v1/posts/" + carolPost + "/comments", carol)
                        .getBody()
                        .path("items")))
                .containsExactly("bob was here", "alice was here");

        // Unblocking makes them visible again; the follows do not come back by themselves.
        assertThat(api.delete("/api/v1/users/" + bob.id() + "/block", alice).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.delete("/api/v1/users/" + bob.id() + "/block", alice).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode again = profile(alice, bob);
        assertThat(again.path("isFollowing").asBoolean()).isFalse();
        assertThat(again.path("counts").path("followers").asInt()).isZero();
        assertThat(api.put("/api/v1/posts/" + alicePost + "/like", null, bob).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(follow(bob, alice)).isEqualTo("FOLLOWING");

        // Not possible: yourself, or someone who does not exist.
        assertThat(api.put("/api/v1/users/" + alice.id() + "/block", null, alice)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("CANNOT_BLOCK_SELF");
        assertThat(api.put("/api/v1/users/" + UUID.randomUUID() + "/block", null, alice)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Follows and returns what the API said: "FOLLOWING" (200: the follow endpoint answers with
     * the state since follow requests; it was 204 before), or the error status.
     */
    private String follow(TestApi.User follower, TestApi.User followee) {
        ResponseEntity<JsonNode> response = api.put("/api/v1/users/" + followee.id() + "/follow", null, follower);
        return response.getStatusCode() == HttpStatus.OK
                ? response.getBody().path("state").asText("")
                : response.getStatusCode().toString();
    }

    private JsonNode profile(TestApi.User of, TestApi.User viewer) {
        ResponseEntity<JsonNode> response = api.get("/api/v1/users/" + of.handle(), viewer);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private String post(TestApi.User author) {
        ResponseEntity<JsonNode> created = api.post(
                "/api/v1/posts", Map.of("clips", List.of(Map.of("mediaId", data.video(author.id(), "READY")))), author);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().path("id").asText("");
    }

    private void comment(TestApi.User author, String postId, String body) {
        assertThat(api.post("/api/v1/posts/" + postId + "/comments", Map.of("body", body), author)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    private static List<String> handles(JsonNode users) {
        List<String> handles = new ArrayList<>();
        users.forEach(user -> handles.add(user.path("handle").asText("")));
        return handles;
    }

    private static List<String> bodies(JsonNode comments) {
        List<String> bodies = new ArrayList<>();
        comments.forEach(comment -> bodies.add(comment.path("body").asText("")));
        return bodies;
    }
}
