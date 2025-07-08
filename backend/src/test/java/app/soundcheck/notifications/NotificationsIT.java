package app.soundcheck.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.content.CommentAdded;
import app.soundcheck.content.PostFailed;
import app.soundcheck.content.PostLiked;
import app.soundcheck.content.PostPublished;
import app.soundcheck.social.UserFollowed;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.http.HttpStatus;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The first tests use Spring Modulith's {@link Scenario}: publish one domain event
 * the way a module would (inside a transaction) and wait for the state it should cause, which here is a
 * row in someone's inbox. The last one goes through the real endpoints end to end.
 */
@EnableScenarios
class NotificationsIT extends IntegrationTest {

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
    TestApi.User author;
    TestApi.User fan;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        author = api.register("author");
        fan = api.register("fan");
    }

    @Test
    void aFollowNotifiesTheFollowedUser(Scenario scenario) {
        scenario.publish(new UserFollowed(fan.id(), author.id()))
                .andWaitForStateChange(() -> inbox(author), items -> !items.isEmpty())
                .andVerify(items -> {
                    assertThat(items).hasSize(1);
                    assertThat(items.getFirst().path("type").asText("")).isEqualTo("NEW_FOLLOWER");
                    assertThat(items.getFirst().path("actor").path("handle").asText(""))
                            .isEqualTo(fan.handle());
                    assertThat(items.getFirst().path("read").asBoolean()).isFalse();
                });
        assertThat(inbox(fan)).isEmpty(); // the follower hears nothing
    }

    @Test
    void aLikeAndACommentNotifyThePostsAuthor(Scenario scenario) {
        UUID post = UUID.randomUUID();
        UUID comment = UUID.randomUUID();

        scenario.publish(new PostLiked(post, author.id(), fan.id()))
                .andWaitForStateChange(() -> inbox(author), items -> items.size() == 1)
                .andVerify(items -> {
                    assertThat(items.getFirst().path("type").asText("")).isEqualTo("POST_LIKED");
                    assertThat(items.getFirst().path("postId").asText("")).isEqualTo(post.toString());
                    assertThat(items.getFirst().path("actor").path("handle").asText(""))
                            .isEqualTo(fan.handle());
                });
        scenario.publish(new CommentAdded(post, author.id(), comment, fan.id()))
                .andWaitForStateChange(() -> inbox(author), items -> items.size() == 2)
                .andVerify(items -> {
                    // Newest first.
                    assertThat(items.getFirst().path("type").asText("")).isEqualTo("POST_COMMENTED");
                    assertThat(items.getFirst().path("commentId").asText("")).isEqualTo(comment.toString());
                });
    }

    @Test
    void aPostThatWasProcessingNotifiesItsAuthorWhenReadyOrFailed(Scenario scenario) {
        UUID ready = UUID.randomUUID();
        UUID failed = UUID.randomUUID();

        scenario.publish(new PostPublished(ready, author.id(), Instant.now(), true))
                .andWaitForStateChange(() -> inbox(author), items -> items.size() == 1)
                .andVerify(items -> {
                    assertThat(items.getFirst().path("type").asText("")).isEqualTo("POST_READY");
                    assertThat(items.getFirst().path("postId").asText("")).isEqualTo(ready.toString());
                    assertThat(items.getFirst().path("actor").isNull()).isTrue();
                });
        scenario.publish(new PostFailed(failed, author.id()))
                .andWaitForStateChange(() -> inbox(author), items -> items.size() == 2)
                .andVerify(items ->
                        assertThat(items.getFirst().path("type").asText("")).isEqualTo("POST_FAILED"));
    }

    @Test
    void nothingIsRecordedForOnesOwnActionsOrForAPostPublishedAtOnce() {
        UUID post = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            events.publishEvent(new PostLiked(post, author.id(), author.id())); // liked their own post
            events.publishEvent(new CommentAdded(post, author.id(), UUID.randomUUID(), author.id()));
            events.publishEvent(new PostPublished(post, author.id(), Instant.now(), false)); // clip was READY
            events.publishEvent(new UserFollowed(author.id(), fan.id())); // a marker: this one must arrive
        });

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(inbox(fan)).hasSize(1));
        assertThat(inbox(author)).isEmpty();
    }

    /** The outbox delivers at least once: a second delivery of the same event must add nothing (V013). */
    @Test
    void anEventDeliveredTwiceLeavesOneEntry() {
        UUID post = UUID.randomUUID();
        UUID failedPost = UUID.randomUUID();
        UUID comment = UUID.randomUUID();
        Instant publishedAt = Instant.now();
        for (int delivery = 0; delivery < 2; delivery++) {
            tx.executeWithoutResult(status -> {
                events.publishEvent(new UserFollowed(fan.id(), author.id()));
                events.publishEvent(new CommentAdded(post, author.id(), comment, fan.id()));
                events.publishEvent(new PostPublished(post, author.id(), publishedAt, true));
                events.publishEvent(new PostFailed(failedPost, author.id()));
            });
        }
        // A different comment is a different event.
        tx.executeWithoutResult(
                status -> events.publishEvent(new CommentAdded(post, author.id(), UUID.randomUUID(), fan.id())));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(inbox(author)).hasSize(5));
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(
                        types(author))
                .containsExactlyInAnyOrder(
                        "NEW_FOLLOWER", "POST_COMMENTED", "POST_COMMENTED", "POST_READY", "POST_FAILED"));
        assertThat(unread(author)).isEqualTo(5);
    }

    /** A block makes the two invisible to each other in the inbox as well. */
    @Test
    void aBlockRemovesWhatTheTwoInboxesSayAboutEachOther() {
        TestApi.User bystander = api.register("bystander");
        tx.executeWithoutResult(status -> {
            events.publishEvent(new UserFollowed(fan.id(), author.id()));
            events.publishEvent(new UserFollowed(author.id(), fan.id()));
            events.publishEvent(new UserFollowed(bystander.id(), author.id()));
        });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(inbox(author)).hasSize(2);
            assertThat(inbox(fan)).hasSize(1);
        });

        assertThat(api.put("/api/v1/users/" + fan.id() + "/block", null, author).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(inbox(fan)).isEmpty();
            assertThat(inbox(author)).hasSize(1);
        });
        assertThat(inbox(author).getFirst().path("actor").path("handle").asText(""))
                .isEqualTo(bystander.handle());
        assertThat(unread(author)).isEqualTo(1);
    }

    @Test
    void likesOfOnePostAreCollapsedWhileUnread(Scenario scenario) {
        TestApi.User second = api.register("second");
        TestApi.User third = api.register("third");
        UUID post = UUID.randomUUID();
        UUID otherPost = UUID.randomUUID();

        scenario.publish(new PostLiked(post, author.id(), fan.id()))
                .andWaitForStateChange(() -> inbox(author), items -> items.size() == 1);
        // A second like of the same post while the first is unread adds nothing; a like of another post does.
        tx.executeWithoutResult(status -> {
            events.publishEvent(new PostLiked(post, author.id(), second.id()));
            events.publishEvent(new PostLiked(otherPost, author.id(), second.id()));
        });
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(inbox(author)).hasSize(2));
        await().during(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(inbox(author)).hasSize(2));
        assertThat(unread(author)).isEqualTo(2);

        // Once read, the next like of that post is news again.
        String newest = inbox(author).getFirst().path("id").asText("");
        assertThat(api.post("/api/v1/notifications/read", Map.of("upTo", newest), author)
                        .getBody()
                        .path("unreadCount")
                        .asInt())
                .isZero();
        scenario.publish(new PostLiked(post, author.id(), third.id()))
                .andWaitForStateChange(() -> inbox(author), items -> items.size() == 3)
                .andVerify(items -> {
                    assertThat(items.getFirst().path("read").asBoolean()).isFalse();
                    assertThat(items.getFirst().path("actor").path("handle").asText(""))
                            .isEqualTo(third.handle());
                    assertThat(items.get(1).path("read").asBoolean()).isTrue();
                });
        assertThat(unread(author)).isEqualTo(1);
    }

    @Test
    void realFollowsLikesAndCommentsArriveInTheInboxAndCanBeMarkedRead() {
        TestData data = new TestData(catalogIngest, dsl, tx, events);
        UUID clip = data.video(author.id(), "UPLOADED");
        String postId = api.post("/api/v1/posts", Map.of("mediaId", clip), author)
                .getBody()
                .path("id")
                .asText("");
        data.workerFinishes(clip, author.id()); // → PostPublished(afterProcessing) → POST_READY
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(types(author)).containsExactly("POST_READY"));

        api.put("/api/v1/users/" + author.id() + "/follow", null, fan);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(types(author)).hasSize(2));
        api.put("/api/v1/posts/" + postId + "/like", null, fan);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(types(author)).hasSize(3));
        api.post("/api/v1/posts/" + postId + "/comments", Map.of("body", "Great set"), fan);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(types(author))
                .containsExactly("POST_COMMENTED", "POST_LIKED", "NEW_FOLLOWER", "POST_READY"));
        // Repeating the follow and the like publishes no event, so nothing new arrives.
        api.put("/api/v1/users/" + author.id() + "/follow", null, fan);
        api.put("/api/v1/posts/" + postId + "/like", null, fan);

        JsonNode page = api.get("/api/v1/notifications?limit=3", author).getBody();
        assertThat(page.path("unreadCount").asInt()).isEqualTo(4);
        assertThat(page.path("items").size()).isEqualTo(3);
        JsonNode like = page.path("items").get(1);
        assertThat(like.path("likeCount").asInt()).isEqualTo(1); // the post's likes now
        assertThat(like.path("postThumbUrl").asText("")).endsWith("/media/" + clip + "/v1/thumb.webp");
        assertThat(like.path("actor").path("handle").asText("")).isEqualTo(fan.handle());
        JsonNode next = api.get(
                        "/api/v1/notifications?limit=3&cursor="
                                + page.path("nextCursor").asText(""),
                        author)
                .getBody();
        assertThat(next.path("items").size()).isEqualTo(1);
        assertThat(next.path("nextCursor").isNull()).isTrue();

        // Read up to the like: the like, the follow and the ready notice are read; the comment is not.
        JsonNode afterRead = api.post(
                        "/api/v1/notifications/read",
                        Map.of("upTo", like.path("id").asText("")),
                        author)
                .getBody();
        assertThat(afterRead.path("unreadCount").asInt()).isEqualTo(1);
        List<Boolean> read = new ArrayList<>();
        api.get("/api/v1/notifications", author)
                .getBody()
                .path("items")
                .forEach(n -> read.add(n.path("read").asBoolean()));
        assertThat(read).containsExactly(false, true, true, true);

        // Someone else's notification id is not found; a missing body field is a 400.
        assertThat(api.post(
                                "/api/v1/notifications/read",
                                Map.of("upTo", like.path("id").asText("")),
                                fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.post("/api/v1/notifications/read", Map.of(), author).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.get("/api/v1/notifications", fan)
                        .getBody()
                        .path("unreadCount")
                        .asInt())
                .isZero();
    }

    /** Opening one entry marks that entry read, and nothing newer or older with it. */
    @Test
    void openingOneNotificationMarksOnlyThatOneRead() {
        TestApi.User second = api.register("second");
        TestApi.User third = api.register("third");
        tx.executeWithoutResult(status -> events.publishEvent(new UserFollowed(fan.id(), author.id())));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(inbox(author)).hasSize(1));
        tx.executeWithoutResult(status -> events.publishEvent(new UserFollowed(second.id(), author.id())));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(inbox(author)).hasSize(2));
        tx.executeWithoutResult(status -> events.publishEvent(new UserFollowed(third.id(), author.id())));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(inbox(author)).hasSize(3));
        String middle = inbox(author).get(1).path("id").asText("");

        JsonNode answer = api.post("/api/v1/notifications/" + middle + "/read", null, author)
                .getBody();

        assertThat(answer.path("unreadCount").asInt()).isEqualTo(2);
        assertThat(inbox(author).stream().map(n -> n.path("read").asBoolean())).containsExactly(false, true, false);
        // Opening it again changes nothing.
        assertThat(api.post("/api/v1/notifications/" + middle + "/read", null, author)
                        .getBody()
                        .path("unreadCount")
                        .asInt())
                .isEqualTo(2);
        // Someone else's entry, or one that does not exist, is not found and stays unread.
        assertThat(api.post(
                                "/api/v1/notifications/"
                                        + inbox(author).getFirst().path("id").asText("") + "/read",
                                null,
                                fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.post("/api/v1/notifications/" + UUID.randomUUID() + "/read", null, author)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unread(author)).isEqualTo(2);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private List<JsonNode> inbox(TestApi.User user) {
        List<JsonNode> items = new ArrayList<>();
        api.get("/api/v1/notifications", user).getBody().path("items").forEach(items::add);
        return items;
    }

    private List<String> types(TestApi.User user) {
        return inbox(user).stream().map(n -> n.path("type").asText("")).toList();
    }

    private int unread(TestApi.User user) {
        return api.get("/api/v1/notifications", user)
                .getBody()
                .path("unreadCount")
                .asInt();
    }
}
