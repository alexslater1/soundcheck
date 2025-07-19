package app.soundcheck.social;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * People in the Search tab, by handle or display name. The best text match comes first,
 * then the people the viewer follows, then their followers; a block in either direction hides the
 * person; a private profile is found and marked private.
 */
class PeopleSearchIT extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    TestApi api;
    TestApi.User viewer;

    /** A word no other test's users contain, so the search sees only this test's people. */
    String word;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        viewer = api.register("viewer");
        word = "ps" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Test
    void ranksByMatchThenGraphAndHidesBlockedPeople() {
        TestApi.User exact = person(word, "Someone Else");
        TestApi.User stranger = person(word + "_a", "A Stranger");
        TestApi.User friend = person(word + "_b", "A Friend");
        TestApi.User fan = person(word + "_c", "A Fan");
        TestApi.User byName = person("zz" + word.substring(2), "Jo " + word);
        TestApi.User blocker = person(word + "_d", "Blocks You");
        TestApi.User blocked = person(word + "_e", "Blocked By You");

        // 2xx: a follow answers 204 today, and 200 with its state once follow requests land.
        assertThat(api.put("/api/v1/users/" + friend.id() + "/follow", null, viewer)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(api.put("/api/v1/users/" + viewer.id() + "/follow", null, fan)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(api.put("/api/v1/users/" + viewer.id() + "/block", null, blocker)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.put("/api/v1/users/" + blocked.id() + "/block", null, viewer)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode found = ok(api.get("/api/v1/search/people?q=" + word, viewer));
        // Exact handle; handles that start with the word (followed, follower, stranger); the display name.
        assertThat(handles(found))
                .containsExactly(exact.handle(), friend.handle(), fan.handle(), stranger.handle(), byName.handle());
        assertThat(found.path(1).path("user").path("isFollowing").asBoolean()).isTrue();
        assertThat(found.path(2).path("user").path("isFollowing").asBoolean()).isFalse();
        assertThat(found.path(0).path("user").path("displayName").asText("")).isEqualTo("Someone Else");
        assertThat(found.path(0).path("profileVisibility").asText("")).isEqualTo("PUBLIC");

        // A leading @ is a handle, as typed in a caption; upper case matches too.
        assertThat(handles(ok(api.get("/api/v1/search/people?q=@" + word.toUpperCase(), viewer))))
                .startsWith(exact.handle());
        // A word of the display name finds the person; the limit caps the list.
        assertThat(handles(ok(api.get("/api/v1/search/people?q=jo " + word, viewer))))
                .first()
                .isEqualTo(byName.handle());
        assertThat(ok(api.get("/api/v1/search/people?limit=2&q=" + word, viewer))
                        .size())
                .isEqualTo(2);
        // The blocked see each other in no direction.
        assertThat(handles(ok(api.get("/api/v1/search/people?q=" + viewer.handle(), blocker))))
                .doesNotContain(viewer.handle());
        assertThat(handles(ok(api.get("/api/v1/search/people?q=" + viewer.handle(), blocked))))
                .doesNotContain(viewer.handle());
        assertThat(handles(ok(api.get("/api/v1/search/people?q=" + viewer.handle(), stranger))))
                .containsExactly(viewer.handle());
    }

    @Test
    void theSearcherNeverFindsThemselves() {
        TestApi.User named = person(word + "_me", "Me Myself");
        TestApi.User other = person(word + "_you", "Someone Else");
        // Both match the word; the caller's own account is left out, anyone else's is not.
        assertThat(handles(ok(api.get("/api/v1/search/people?q=" + word, named))))
                .containsExactly(other.handle());
        assertThat(handles(ok(api.get("/api/v1/search/people?q=" + word, other))))
                .containsExactly(named.handle());
    }

    @Test
    void privateProfilesAreFoundAndMarkedAndDeletedAccountsAreNot() {
        TestApi.User owner = person(word + "_p", "Private Person");
        TestApi.User gone = person(word + "_x", "Gone Person");
        assertThat(ok(api.patch("/api/v1/me", Map.of("profileVisibility", "PRIVATE"), owner))
                        .path("profileVisibility")
                        .asText(""))
                .isEqualTo("PRIVATE");
        assertThat(api.call(
                                org.springframework.http.HttpMethod.DELETE,
                                "/api/v1/me",
                                Map.of("password", "correct horse battery"),
                                gone)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();

        JsonNode found = ok(api.get("/api/v1/search/people?q=" + word, viewer));
        assertThat(handles(found)).containsExactly(owner.handle());
        assertThat(found.path(0).path("profileVisibility").asText("")).isEqualTo("PRIVATE");
        assertThat(api.get("/api/v1/search/people?q=ab", viewer).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // Three characters with the @, two without it: too short for the index, so nobody.
        assertThat(ok(api.get("/api/v1/search/people?q=@" + word.substring(0, 2), viewer))
                        .size())
                .isZero();
    }

    /** A new account with the given handle and display name. */
    private TestApi.User person(String handle, String displayName) {
        TestApi.User user = api.register("p");
        JsonNode me = ok(api.patch("/api/v1/me", Map.of("handle", handle, "displayName", displayName), user));
        assertThat(me.path("handle").asText("")).isEqualTo(handle);
        return new TestApi.User(user.id(), handle, user.email(), user.accessToken());
    }

    private static List<String> handles(JsonNode people) {
        return people.valueStream()
                .map(p -> p.path("user").path("handle").asText(""))
                .toList();
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
