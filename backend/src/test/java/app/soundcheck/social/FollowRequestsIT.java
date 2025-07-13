package app.soundcheck.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Follow requests over HTTP against real PostGIS: following a private profile asks its
 * owner, who accepts (the requester follows and is told) or declines (nobody is told); the requester can
 * cancel; turning public accepts whatever is waiting; a block removes requests. The Activity entries
 * arrive through after-commit listeners, so the inbox is awaited.
 */
class FollowRequestsIT extends IntegrationTest {

    static final Duration EVENTS = Duration.ofSeconds(10);

    @Autowired
    TestRestTemplate rest;

    TestApi api;
    TestApi.User owner;
    TestApi.User asker;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        owner = api.register("owner");
        asker = api.register("asker");
    }

    @Test
    void followingAPublicProfileFollowsAtOnce() {
        assertThat(follow(asker, owner)).isEqualTo("FOLLOWING");
        assertThat(follow(asker, owner)).isEqualTo("FOLLOWING");

        JsonNode profile = profile(asker);
        assertThat(profile.path("isFollowing").asBoolean()).isTrue();
        assertThat(profile.path("isRequested").asBoolean()).isFalse();
        assertThat(profile.path("counts").path("followers").asLong()).isEqualTo(1);
    }

    @Test
    void followingAPrivateProfileAsksAndAcceptingLetsTheRequesterIn() {
        makePrivate();

        assertThat(follow(asker, owner)).isEqualTo("REQUESTED");
        assertThat(follow(asker, owner)).as("asking twice").isEqualTo("REQUESTED");
        JsonNode waiting = profile(asker);
        assertThat(waiting.path("isRequested").asBoolean()).isTrue();
        assertThat(waiting.path("isFollowing").asBoolean()).isFalse();
        assertThat(waiting.path("contentVisible").asBoolean()).isFalse();
        // A request is not a follow: it counts nowhere and opens nothing.
        assertThat(waiting.path("counts").path("followers").asLong()).isZero();
        assertProblem(
                api.get("/api/v1/users/" + owner.id() + "/posts", asker), HttpStatus.FORBIDDEN, "PROFILE_PRIVATE");

        // The owner's Activity: one entry, waiting for an answer (asking twice added nothing).
        await().atMost(EVENTS).until(() -> entries(owner, "FOLLOW_REQUEST").size() == 1);
        JsonNode request = entries(owner, "FOLLOW_REQUEST").getFirst();
        assertThat(request.path("actor").path("id").asText(""))
                .isEqualTo(asker.id().toString());
        assertThat(request.path("requestPending").asBoolean()).isTrue();
        assertThat(request.path("read").asBoolean()).isFalse();

        assertThat(accept(owner, asker).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode in = profile(asker);
        assertThat(in.path("isFollowing").asBoolean()).isTrue();
        assertThat(in.path("isRequested").asBoolean()).isFalse();
        assertThat(in.path("contentVisible").asBoolean()).isTrue();
        assertThat(in.path("counts").path("followers").asLong()).isEqualTo(1);
        assertThat(api.get("/api/v1/users/" + owner.id() + "/posts", asker)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        // Accepting again changes nothing.
        assertThat(accept(owner, asker).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The requester is told; the owner's entry now reads "started following you", already read.
        await().atMost(EVENTS).until(() -> entries(asker, "FOLLOW_ACCEPTED").size() == 1);
        assertThat(entries(asker, "FOLLOW_ACCEPTED")
                        .getFirst()
                        .path("actor")
                        .path("id")
                        .asText(""))
                .isEqualTo(owner.id().toString());
        await().atMost(EVENTS).until(() -> entries(owner, "FOLLOW_REQUEST").isEmpty());
        List<JsonNode> followed = entries(owner, "NEW_FOLLOWER");
        assertThat(followed).hasSize(1);
        assertThat(followed.getFirst().path("read").asBoolean()).isTrue();
        assertThat(followed.getFirst().path("requestPending").isNull()).isTrue();
    }

    @Test
    void decliningRemovesTheRequestWithoutTellingTheRequester() {
        makePrivate();
        follow(asker, owner);
        await().atMost(EVENTS).until(() -> entries(owner, "FOLLOW_REQUEST").size() == 1);

        assertThat(api.delete("/api/v1/me/follow-requests/" + asker.id(), owner).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(profile(asker).path("isRequested").asBoolean()).isFalse();
        await().atMost(EVENTS).until(() -> entries(owner, "FOLLOW_REQUEST").isEmpty());
        assertThat(entries(asker, "FOLLOW_ACCEPTED")).isEmpty();
        // Nothing left to accept; declining again is fine.
        assertProblem(accept(owner, asker), HttpStatus.NOT_FOUND, "FOLLOW_REQUEST_NOT_FOUND");
        assertThat(api.delete("/api/v1/me/follow-requests/" + asker.id(), owner).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        // They may ask again.
        assertThat(follow(asker, owner)).isEqualTo("REQUESTED");
    }

    @Test
    void theRequesterCanCancel() {
        makePrivate();
        follow(asker, owner);
        await().atMost(EVENTS).until(() -> entries(owner, "FOLLOW_REQUEST").size() == 1);

        assertThat(api.delete("/api/v1/users/" + owner.id() + "/follow", asker).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(profile(asker).path("isRequested").asBoolean()).isFalse();
        await().atMost(EVENTS).until(() -> entries(owner, "FOLLOW_REQUEST").isEmpty());
        assertProblem(accept(owner, asker), HttpStatus.NOT_FOUND, "FOLLOW_REQUEST_NOT_FOUND");
    }

    @Test
    void turningPublicAcceptsEveryWaitingRequest() {
        TestApi.User second = api.register("second");
        makePrivate();
        follow(asker, owner);
        follow(second, owner);

        ok(api.patch("/api/v1/me", Map.of("profileVisibility", "PUBLIC"), owner));

        await().atMost(EVENTS)
                .until(() -> profile(asker).path("isFollowing").asBoolean()
                        && profile(second).path("isFollowing").asBoolean());
        assertThat(profile(asker).path("isRequested").asBoolean()).isFalse();
        await().atMost(EVENTS)
                .until(() -> entries(asker, "FOLLOW_ACCEPTED").size() == 1
                        && entries(second, "FOLLOW_ACCEPTED").size() == 1);
    }

    @Test
    void existingFollowersStayWhenTheProfileTurnsPrivateAndCanBeRemoved() {
        follow(asker, owner);
        makePrivate();

        assertThat(profile(asker).path("contentVisible").asBoolean()).isTrue();

        assertThat(api.delete("/api/v1/me/followers/" + asker.id(), owner).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode removed = profile(asker);
        assertThat(removed.path("isFollowing").asBoolean()).isFalse();
        assertThat(removed.path("contentVisible").asBoolean()).isFalse();
        // Following again is a request now.
        assertThat(follow(asker, owner)).isEqualTo("REQUESTED");
    }

    @Test
    void aBlockRemovesTheRequestAndTheOwnersEntry() {
        makePrivate();
        follow(asker, owner);
        await().atMost(EVENTS).until(() -> entries(owner, "FOLLOW_REQUEST").size() == 1);

        api.put("/api/v1/users/" + asker.id() + "/block", null, owner);
        api.delete("/api/v1/users/" + asker.id() + "/block", owner);

        assertThat(profile(asker).path("isRequested").asBoolean()).isFalse();
        await().atMost(EVENTS).until(() -> entries(owner, "FOLLOW_REQUEST").isEmpty());
        assertProblem(accept(owner, asker), HttpStatus.NOT_FOUND, "FOLLOW_REQUEST_NOT_FOUND");
    }

    private String follow(TestApi.User who, TestApi.User whom) {
        return ok(api.put("/api/v1/users/" + whom.id() + "/follow", null, who))
                .path("state")
                .asText("");
    }

    private ResponseEntity<JsonNode> accept(TestApi.User who, TestApi.User requester) {
        return api.post("/api/v1/me/follow-requests/" + requester.id() + "/accept", null, who);
    }

    private void makePrivate() {
        ok(api.patch("/api/v1/me", Map.of("profileVisibility", "PRIVATE"), owner));
    }

    private JsonNode profile(TestApi.User viewer) {
        return ok(api.get("/api/v1/users/" + owner.handle(), viewer));
    }

    private List<JsonNode> entries(TestApi.User user, String type) {
        List<JsonNode> found = new ArrayList<>();
        ok(api.get("/api/v1/notifications", user)).path("items").forEach(item -> {
            if (item.path("type").asText("").equals(type)) {
                found.add(item);
            }
        });
        return found;
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
