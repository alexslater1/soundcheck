package app.soundcheck.content;

import static app.soundcheck.content.PostClipsIT.clip;
import static app.soundcheck.content.PostClipsIT.created;
import static app.soundcheck.content.PostClipsIT.ids;
import static app.soundcheck.content.PostClipsIT.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.soundcheck.catalog.CatalogIngest;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Over HTTP: tagging the people you went with. Only people the author follows; they
 * are told when the post is live; the post says "with @name"; a tagged person can take the tag off (and
 * cannot be tagged on that post again), put the post on their profile, or add clips of their own, which
 * mark them Went and reach their followers.
 */
class PostTagsIT extends IntegrationTest {

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
    TestApi.User friend;
    TestApi.User stranger;
    UUID gig;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        author = api.register("tagger");
        friend = api.register("mate");
        stranger = api.register("other");
        String tag = UUID.randomUUID().toString().substring(0, 8);
        gig = data.gig(
                "Night " + tag,
                data.venue("Brudenell " + tag),
                Instant.parse("2026-08-20T19:30:00Z"),
                data.artist("Act " + tag));
        api.put("/api/v1/users/" + friend.id() + "/follow", null, author);
    }

    @Test
    void onlyPeopleTheAuthorFollowsCanBeTaggedAndTheyAreToldOnceThePostIsLive() {
        assertThat(api.post(
                                "/api/v1/posts",
                                Map.of(
                                        "clips",
                                        List.of(clip(data.video(author.id(), "READY"), null, null)),
                                        "gigId",
                                        gig,
                                        "taggedUserIds",
                                        List.of(stranger.id())),
                                author)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("TAG_NOT_ALLOWED");

        UUID clip = data.video(author.id(), "PROCESSING");
        JsonNode post = created(api.post(
                "/api/v1/posts",
                Map.of("clips", List.of(clip(clip, null, null)), "gigId", gig, "taggedUserIds", List.of(friend.id())),
                author));
        String postId = post.path("id").asText("");
        // Not told while the post is still processing.
        assertThat(tagNotices(friend)).isEmpty();

        data.workerFinishes(clip, author.id());
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(tagNotices(friend)).containsExactly(postId));

        JsonNode seen = ok(api.get("/api/v1/posts/" + postId, stranger));
        assertThat(seen.path("withUsers").get(0).path("id").asText(""))
                .isEqualTo(friend.id().toString());
        assertThat(seen.path("myTag").isNull()).isTrue();
        assertThat(ok(api.get("/api/v1/posts/" + postId, friend)).path("myTag").asText(""))
                .isEqualTo("TAGGED");

        // An edit that tags someone new on a live post tells only them.
        TestApi.User another = api.register("another");
        api.put("/api/v1/users/" + another.id() + "/follow", null, author);
        ok(api.patch(
                "/api/v1/posts/" + postId,
                Map.of(
                        "taggedUserIds",
                        List.of(friend.id().toString(), another.id().toString())),
                author));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(tagNotices(another)).containsExactly(postId));
        assertThat(tagNotices(friend)).containsExactly(postId);
    }

    @Test
    void aTaggedPersonCanLeaveAndCannotBeTaggedAgain() {
        String postId = taggedPost();
        assertThat(api.delete("/api/v1/posts/" + postId + "/tags/me", stranger).getStatusCode())
                .as("only a tagged person can leave")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.delete("/api/v1/posts/" + postId + "/tags/me", friend).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(ok(api.get("/api/v1/posts/" + postId, stranger))
                        .path("withUsers")
                        .isEmpty())
                .isTrue();

        ok(api.patch(
                "/api/v1/posts/" + postId,
                Map.of("taggedUserIds", List.of(friend.id().toString())),
                author));
        assertThat(ok(api.get("/api/v1/posts/" + postId, stranger))
                        .path("withUsers")
                        .isEmpty())
                .as("a removed tag stays removed")
                .isTrue();
    }

    @Test
    void aTaggedPersonCanPutThePostOnTheirProfileAndAddTheirOwnClips() {
        String postId = taggedPost();
        String profile = "/api/v1/users/" + friend.id() + "/posts";
        assertThat(ids(ok(api.get(profile, stranger)).path("items"))).isEmpty();

        assertThat(ok(api.put("/api/v1/posts/" + postId + "/tags/me", Map.of("onProfile", true), friend))
                        .path("myTag")
                        .asText(""))
                .isEqualTo("ON_PROFILE");
        assertThat(ids(ok(api.get(profile, stranger)).path("items"))).containsExactly(postId);
        ok(api.put("/api/v1/posts/" + postId + "/tags/me", Map.of("onProfile", false), friend));
        assertThat(ids(ok(api.get(profile, stranger)).path("items"))).isEmpty();

        // A stranger cannot add clips; the tagged friend can, which puts the post on their profile.
        assertThat(api.post(
                                "/api/v1/posts/" + postId + "/clips",
                                Map.of("clips", List.of(clip(data.video(stranger.id(), "READY"), null, null))),
                                stranger)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode withFriend = ok(api.post(
                "/api/v1/posts/" + postId + "/clips",
                Map.of("clips", List.of(clip(data.video(friend.id(), "READY"), null, null))),
                friend));
        List<String> owners = new ArrayList<>();
        withFriend
                .path("clips")
                .forEach(c -> owners.add(c.path("owner").path("id").asText("")));
        assertThat(owners).containsExactly(author.id().toString(), friend.id().toString());
        assertThat(ids(ok(api.get(profile, stranger)).path("items"))).containsExactly(postId);
        // Their live clip marks them Went, as their own post would.
        assertThat(api.get("/api/v1/gigs/" + gig, friend)
                        .getBody()
                        .path("gig")
                        .path("myAttendance")
                        .asText(""))
                .isEqualTo("WENT");
        // Their followers get the post in Following.
        api.put("/api/v1/users/" + friend.id() + "/follow", null, stranger);
        assertThat(ids(ok(api.get("/api/v1/feed/following", stranger)).path("items")))
                .contains(postId);
        // Following its author too, the post is in both of byAuthors' branches: it comes once.
        api.put("/api/v1/users/" + author.id() + "/follow", null, stranger);
        assertThat(ids(ok(api.get("/api/v1/feed/following", stranger)).path("items")))
                .containsOnlyOnce(postId);
        api.delete("/api/v1/users/" + author.id() + "/follow", stranger);

        // The author can remove the friend's clip.
        String friendsClip = withFriend.path("clips").get(1).path("id").asText("");
        assertThat(api.delete("/api/v1/posts/" + postId + "/clips/" + friendsClip, author)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.get("/api/v1/gigs/" + gig, friend)
                        .getBody()
                        .path("gig")
                        .path("myAttendance")
                        .isNull())
                .as("the Went only their clip made goes with it")
                .isTrue();
    }

    @Test
    void aTaggedPersonWhoBlocksTheViewerIsNotShownToThem() {
        String postId = taggedPost();
        api.put("/api/v1/users/" + stranger.id() + "/block", null, friend);
        assertThat(ok(api.get("/api/v1/posts/" + postId, stranger))
                        .path("withUsers")
                        .isEmpty())
                .isTrue();
    }

    private String taggedPost() {
        return created(api.post(
                        "/api/v1/posts",
                        Map.of(
                                "clips",
                                List.of(clip(data.video(author.id(), "READY"), null, null)),
                                "gigId",
                                gig,
                                "taggedUserIds",
                                List.of(friend.id())),
                        author))
                .path("id")
                .asText("");
    }

    private List<String> tagNotices(TestApi.User user) {
        List<String> posts = new ArrayList<>();
        api.get("/api/v1/notifications", user).getBody().path("items").forEach(n -> {
            if (n.path("type").asText("").equals("POST_TAGGED")) {
                posts.add(n.path("postId").asText(""));
            }
        });
        return posts;
    }
}
