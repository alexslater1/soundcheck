package app.soundcheck.content;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
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
 * Private profiles through every read path, over HTTP against real PostGIS: a
 * private profile's posts, profile details and engagement are for the owner and its followers; everyone
 * else sees a name, an avatar, counts, and, on a gig page, how many clips they cannot see. Four people: the
 * private {@code owner}; a {@code follower} who followed the owner while it was public (and so stays a
 * follower); a {@code followee} the owner follows, who does not follow back (being
 * followed by a private profile gives nothing); and a {@code stranger}. Follow
 * requests themselves are in {@code FollowRequestsIT}.
 */
class ProfilePrivacyIT extends IntegrationTest {

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
    TestApi.User owner;
    TestApi.User followee;
    TestApi.User follower;
    TestApi.User stranger;
    UUID act;
    UUID venue;
    UUID gig;
    String ownerClip1;
    String ownerClip2;
    String publicClip;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        owner = api.register("owner");
        followee = api.register("followee");
        follower = api.register("follower");
        stranger = api.register("stranger");
        String tag = UUID.randomUUID().toString().substring(0, 8);
        act = data.artist("Private Act " + tag);
        venue = data.venue("Private Rooms " + tag);
        gig = data.gig("Private night " + tag, venue, Instant.parse("2026-09-12T19:30:00Z"), act);
        ownerClip1 = post(owner, "first");
        // The next day of the gig: one fan's clips from one day are one post.
        ownerClip2 = post(owner, "second", 1);
        publicClip = post(stranger, "public");
        api.put("/api/v1/users/" + followee.id() + "/follow", null, owner);
        api.put("/api/v1/users/" + owner.id() + "/follow", null, follower);
        ok(api.patch("/api/v1/me", Map.of("bio", "front row", "homeCityId", city()), owner));
    }

    @Test
    void profilesArePublicByDefaultAndEveryoneSeesEverything() {
        assertThat(ok(api.get("/api/v1/me", owner)).path("profileVisibility").asText(""))
                .isEqualTo("PUBLIC");
        JsonNode profile = profile(stranger);
        assertThat(profile.path("profileVisibility").asText("")).isEqualTo("PUBLIC");
        assertThat(profile.path("contentVisible").asBoolean()).isTrue();
        assertThat(profile.path("bio").asText("")).isEqualTo("front row");
        assertThat(ids(ok(api.get("/api/v1/users/" + owner.id() + "/posts", stranger))))
                .containsExactly(ownerClip2, ownerClip1);
        assertThat(ids(ok(api.get("/api/v1/gigs/" + gig + "/posts", stranger))))
                .containsExactly(publicClip, ownerClip2, ownerClip1);
        JsonNode detail = ok(api.get("/api/v1/gigs/" + gig, stranger));
        assertThat(detail.path("postsCount").asLong()).isEqualTo(3);
        assertThat(detail.path("privatePostsCount").asLong()).isZero();
    }

    @Test
    void aPrivateProfileShowsOnlyItsNameAvatarAndCountsToSomeoneWhoDoesNotFollowIt() {
        makePrivate();

        for (TestApi.User outsider : List.of(stranger, followee)) {
            JsonNode profile = profile(outsider);
            assertThat(profile.path("profileVisibility").asText("")).isEqualTo("PRIVATE");
            assertThat(profile.path("contentVisible").asBoolean()).isFalse();
            assertThat(profile.path("displayName").asText("")).isNotBlank();
            assertThat(profile.path("bio").isNull()).isTrue();
            assertThat(profile.path("homeCityName").isNull()).isTrue();
            assertThat(profile.path("counts").path("posts").asLong()).isEqualTo(2);
            assertProblem(
                    api.get("/api/v1/users/" + owner.id() + "/posts", outsider),
                    HttpStatus.FORBIDDEN,
                    "PROFILE_PRIVATE");
            assertProblem(
                    api.get("/api/v1/users/" + owner.id() + "/stats", outsider),
                    HttpStatus.FORBIDDEN,
                    "PROFILE_PRIVATE");
            assertProblem(
                    api.get("/api/v1/users/" + owner.id() + "/stats/gigs", outsider),
                    HttpStatus.FORBIDDEN,
                    "PROFILE_PRIVATE");
            // Who the owner follows and who follows them are private too; the counts stay.
            assertProblem(
                    api.get("/api/v1/users/" + owner.id() + "/followers", outsider),
                    HttpStatus.FORBIDDEN,
                    "PROFILE_PRIVATE");
            assertProblem(
                    api.get("/api/v1/users/" + owner.id() + "/following", outsider),
                    HttpStatus.FORBIDDEN,
                    "PROFILE_PRIVATE");
        }

        // The follower and the owner see it all.
        for (TestApi.User insider : List.of(follower, owner)) {
            JsonNode profile = profile(insider);
            assertThat(profile.path("contentVisible").asBoolean()).isTrue();
            assertThat(profile.path("bio").asText("")).isEqualTo("front row");
            assertThat(profile.path("homeCityName").isNull()).isFalse();
            assertThat(ids(ok(api.get("/api/v1/users/" + owner.id() + "/posts", insider))))
                    .containsExactly(ownerClip2, ownerClip1);
            assertThat(ok(api.get("/api/v1/users/" + owner.id() + "/stats", insider))
                            .has("totals"))
                    .isTrue();
            assertThat(ids(ok(api.get("/api/v1/users/" + owner.id() + "/followers", insider))))
                    .containsExactly(follower.id().toString());
            assertThat(ids(ok(api.get("/api/v1/users/" + owner.id() + "/following", insider))))
                    .containsExactly(followee.id().toString());
        }
    }

    @Test
    void aPrivateProfilesCommentsUnderPublicPostsStayVisible() {
        String comment = ok(api.post("/api/v1/posts/" + publicClip + "/comments", Map.of("body", "same night!"), owner))
                .path("id")
                .asText("");
        makePrivate();

        // A comment belongs to the post's conversation, so the switch does not reach it.
        JsonNode comments = ok(api.get("/api/v1/posts/" + publicClip + "/comments", stranger));
        assertThat(ids(comments)).containsExactly(comment);
        assertThat(comments.path("items").path(0).path("author").path("id").asText(""))
                .isEqualTo(owner.id().toString());
    }

    @Test
    void aPrivateProfilesPostsAreHiddenFromEveryListAndLinkOutsideItsCircle() {
        makePrivate();

        // A direct link answers 404, like a hidden post; likes and comments too.
        assertProblem(api.get("/api/v1/posts/" + ownerClip1, stranger), HttpStatus.NOT_FOUND, "POST_NOT_FOUND");
        assertProblem(
                api.put("/api/v1/posts/" + ownerClip1 + "/like", null, followee),
                HttpStatus.NOT_FOUND,
                "POST_NOT_FOUND");
        assertProblem(
                api.post("/api/v1/posts/" + ownerClip1 + "/comments", Map.of("body", "hi"), stranger),
                HttpStatus.NOT_FOUND,
                "POST_NOT_FOUND");
        assertProblem(
                api.get("/api/v1/posts/" + ownerClip1 + "/comments", stranger), HttpStatus.NOT_FOUND, "POST_NOT_FOUND");
        // The follower may.
        assertThat(ok(api.get("/api/v1/posts/" + ownerClip1, follower))
                        .path("id")
                        .asText(""))
                .isEqualTo(ownerClip1);
        assertThat(api.put("/api/v1/posts/" + ownerClip1 + "/like", null, follower)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(api.post("/api/v1/posts/" + ownerClip1 + "/comments", Map.of("body", "great"), follower)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();

        // Gig, artist and venue clip lists.
        for (String list : List.of("/gigs/" + gig, "/artists/" + act, "/venues/" + venue)) {
            assertThat(ids(ok(api.get("/api/v1" + list + "/posts", stranger))))
                    .as(list)
                    .containsExactly(publicClip);
            assertThat(ids(ok(api.get("/api/v1" + list + "/posts", followee))))
                    .as(list)
                    .containsExactly(publicClip);
            assertThat(ids(ok(api.get("/api/v1" + list + "/posts", follower))))
                    .as(list)
                    .containsExactly(publicClip, ownerClip2, ownerClip1);
        }

        // The Following feed: the follower sees the owner's clips; once the owner removes them as a
        // follower (without blocking), they do not, and the owner's profile is closed to them.
        assertThat(ids(ok(api.get("/api/v1/feed/following", follower)))).contains(ownerClip1, ownerClip2);
        assertThat(api.delete("/api/v1/me/followers/" + follower.id(), owner).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(ids(ok(api.get("/api/v1/feed/following", follower)))).doesNotContain(ownerClip1, ownerClip2);
        assertThat(profile(follower).path("contentVisible").asBoolean()).isFalse();
        assertThat(profile(follower).path("isFollowing").asBoolean()).isFalse();
    }

    @Test
    void aGigPageCountsPrivateClipsAndSaysHowManyTheViewerCannotSee() {
        makePrivate();

        JsonNode forStranger = ok(api.get("/api/v1/gigs/" + gig, stranger));
        assertThat(forStranger.path("postsCount").asLong()).isEqualTo(3);
        assertThat(forStranger.path("privatePostsCount").asLong()).isEqualTo(2);
        for (TestApi.User insider : List.of(follower, owner)) {
            JsonNode detail = ok(api.get("/api/v1/gigs/" + gig, insider));
            assertThat(detail.path("postsCount").asLong()).isEqualTo(3);
            assertThat(detail.path("privatePostsCount").asLong()).isZero();
        }
    }

    @Test
    void blocksStillWinAndTheSwitchCanBeTurnedBack() {
        makePrivate();

        // The owner blocks the follower: no profile at all (as before), and the block ended the follow.
        api.put("/api/v1/users/" + follower.id() + "/block", null, owner);
        assertProblem(api.get("/api/v1/users/" + owner.handle(), follower), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        api.delete("/api/v1/users/" + follower.id() + "/block", owner);
        assertThat(profile(follower).path("contentVisible").asBoolean()).isFalse();
        assertProblem(api.get("/api/v1/posts/" + ownerClip1, follower), HttpStatus.NOT_FOUND, "POST_NOT_FOUND");

        // Public again: everyone sees everything, the stranger's gig page has nothing hidden.
        ok(api.patch("/api/v1/me", Map.of("profileVisibility", "PUBLIC"), owner));
        assertThat(profile(stranger).path("contentVisible").asBoolean()).isTrue();
        assertThat(ok(api.get("/api/v1/posts/" + ownerClip1, stranger))
                        .path("id")
                        .asText(""))
                .isEqualTo(ownerClip1);
        assertThat(ok(api.get("/api/v1/gigs/" + gig, stranger))
                        .path("privatePostsCount")
                        .asLong())
                .isZero();
    }

    @Test
    void anUnknownVisibilityIsRefused() {
        assertThat(api.patch("/api/v1/me", Map.of("profileVisibility", "FRIENDS"), owner)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ok(api.get("/api/v1/me", owner)).path("profileVisibility").asText(""))
                .isEqualTo("PUBLIC");
    }

    private void makePrivate() {
        JsonNode me = ok(api.patch("/api/v1/me", Map.of("profileVisibility", "PRIVATE"), owner));
        assertThat(me.path("profileVisibility").asText("")).isEqualTo("PRIVATE");
    }

    private JsonNode profile(TestApi.User viewer) {
        return ok(api.get("/api/v1/users/" + owner.handle(), viewer));
    }

    private int city() {
        return api.get("/api/v1/cities?q=manchester", null)
                .getBody()
                .path(0)
                .path("id")
                .asInt();
    }

    private String post(TestApi.User author, String caption) {
        return post(author, caption, 0);
    }

    private String post(TestApi.User author, String caption, int dayOfGig) {
        return ok(api.post(
                        "/api/v1/posts",
                        Map.of(
                                "clips",
                                List.of(Map.of("mediaId", data.video(author.id(), "READY"))),
                                "caption",
                                caption,
                                "gigId",
                                gig,
                                "day",
                                LocalDate.of(2026, 9, 12).plusDays(dayOfGig).toString(),
                                "artistIds",
                                List.of(act)),
                        author))
                .path("id")
                .asText("");
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asText("")));
        return ids;
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("status %s, body %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody();
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().path("code").asText("")).isEqualTo(code);
    }
}
