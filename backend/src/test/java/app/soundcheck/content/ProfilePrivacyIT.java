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
 * D25b through every read path, over HTTP against real PostGIS: a private profile's posts,
 * profile details and engagement are for the owner and the people the owner follows; everyone else sees a
 * name, an avatar, counts, and, on a gig page, how many clips they cannot see. Four people: the private
 * {@code owner}, a {@code friend} the owner follows, a {@code fan} who follows the owner (not followed
 * back: following a private profile gives nothing), and a {@code stranger}.
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
    TestApi.User friend;
    TestApi.User fan;
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
        friend = api.register("friend");
        fan = api.register("fan");
        stranger = api.register("stranger");
        String tag = UUID.randomUUID().toString().substring(0, 8);
        act = data.artist("Private Act " + tag);
        venue = data.venue("Private Rooms " + tag);
        gig = data.gig("Private night " + tag, venue, Instant.parse("2026-09-12T19:30:00Z"), act);
        ownerClip1 = post(owner, "first");
        ownerClip2 = post(owner, "second");
        publicClip = post(stranger, "public");
        api.put("/api/v1/users/" + friend.id() + "/follow", null, owner);
        api.put("/api/v1/users/" + owner.id() + "/follow", null, fan);
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
    void aPrivateProfileShowsOnlyItsNameAvatarAndCountsToSomeoneItsOwnerDoesNotFollow() {
        makePrivate();

        for (TestApi.User outsider : List.of(stranger, fan)) {
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
        }

        // The friend (followed by the owner) and the owner see it all.
        for (TestApi.User insider : List.of(friend, owner)) {
            JsonNode profile = profile(insider);
            assertThat(profile.path("contentVisible").asBoolean()).isTrue();
            assertThat(profile.path("bio").asText("")).isEqualTo("front row");
            assertThat(profile.path("homeCityName").isNull()).isFalse();
            assertThat(ids(ok(api.get("/api/v1/users/" + owner.id() + "/posts", insider))))
                    .containsExactly(ownerClip2, ownerClip1);
            assertThat(ok(api.get("/api/v1/users/" + owner.id() + "/stats", insider))
                            .has("totals"))
                    .isTrue();
        }
    }

    @Test
    void aPrivateProfilesPostsAreHiddenFromEveryListAndLinkOutsideItsCircle() {
        makePrivate();

        // A direct link answers 404, like a hidden post; likes and comments too.
        assertProblem(api.get("/api/v1/posts/" + ownerClip1, stranger), HttpStatus.NOT_FOUND, "POST_NOT_FOUND");
        assertProblem(
                api.put("/api/v1/posts/" + ownerClip1 + "/like", null, fan), HttpStatus.NOT_FOUND, "POST_NOT_FOUND");
        assertProblem(
                api.post("/api/v1/posts/" + ownerClip1 + "/comments", Map.of("body", "hi"), stranger),
                HttpStatus.NOT_FOUND,
                "POST_NOT_FOUND");
        assertProblem(
                api.get("/api/v1/posts/" + ownerClip1 + "/comments", stranger), HttpStatus.NOT_FOUND, "POST_NOT_FOUND");
        // The friend may.
        assertThat(ok(api.get("/api/v1/posts/" + ownerClip1, friend)).path("id").asText(""))
                .isEqualTo(ownerClip1);
        assertThat(api.put("/api/v1/posts/" + ownerClip1 + "/like", null, friend)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(api.post("/api/v1/posts/" + ownerClip1 + "/comments", Map.of("body", "great"), friend)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();

        // Gig, artist and venue clip lists.
        for (String list : List.of("/gigs/" + gig, "/artists/" + act, "/venues/" + venue)) {
            assertThat(ids(ok(api.get("/api/v1" + list + "/posts", stranger))))
                    .as(list)
                    .containsExactly(publicClip);
            assertThat(ids(ok(api.get("/api/v1" + list + "/posts", friend))))
                    .as(list)
                    .containsExactly(publicClip, ownerClip2, ownerClip1);
        }

        // The Following feed: the fan follows the owner, but the owner does not follow the fan.
        assertThat(ids(ok(api.get("/api/v1/feed/following", fan)))).doesNotContain(ownerClip1, ownerClip2);
        api.put("/api/v1/users/" + fan.id() + "/follow", null, owner);
        assertThat(ids(ok(api.get("/api/v1/feed/following", fan)))).contains(ownerClip1, ownerClip2);
    }

    @Test
    void aGigPageCountsPrivateClipsAndSaysHowManyTheViewerCannotSee() {
        makePrivate();

        JsonNode forStranger = ok(api.get("/api/v1/gigs/" + gig, stranger));
        assertThat(forStranger.path("postsCount").asLong()).isEqualTo(3);
        assertThat(forStranger.path("privatePostsCount").asLong()).isEqualTo(2);
        for (TestApi.User insider : List.of(friend, owner)) {
            JsonNode detail = ok(api.get("/api/v1/gigs/" + gig, insider));
            assertThat(detail.path("postsCount").asLong()).isEqualTo(3);
            assertThat(detail.path("privatePostsCount").asLong()).isZero();
        }
    }

    @Test
    void blocksStillWinAndTheSwitchCanBeTurnedBack() {
        makePrivate();

        // The owner blocks the friend: no profile at all (as before), and the block ended the follow.
        api.put("/api/v1/users/" + friend.id() + "/block", null, owner);
        assertProblem(api.get("/api/v1/users/" + owner.handle(), friend), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        api.delete("/api/v1/users/" + friend.id() + "/block", owner);
        assertThat(profile(friend).path("contentVisible").asBoolean()).isFalse();
        assertProblem(api.get("/api/v1/posts/" + ownerClip1, friend), HttpStatus.NOT_FOUND, "POST_NOT_FOUND");

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
        return ok(api.post(
                        "/api/v1/posts",
                        Map.of(
                                "mediaId",
                                data.video(author.id(), "READY"),
                                "caption",
                                caption,
                                "gigId",
                                gig,
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
