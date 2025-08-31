package app.soundcheck.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
 * The feed inspector. A user's taste as ranking sees it (blended genres, strongest artist and
 * author affinities), and the sessions they were served, item by item with outcomes, newest first.
 */
class FeedInspectorIT extends IntegrationTest {

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

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
    }

    @Test
    void theProfileShowsTheBlendedGenresAndTheStrongestAffinities() {
        TestApi.User user = api.register("inspected");
        TestApi.User author = api.register("liked");
        TestApi.User other = api.register("lessliked");
        api.put("/api/v1/users/" + author.id() + "/follow", null, user);
        api.put("/api/v1/me/genres", Map.of("genres", List.of(Map.of("genreId", 1, "weight", 1.0))), user);
        UUID artist =
                data.artist("Inspector Band " + UUID.randomUUID().toString().substring(0, 6));
        dsl.execute("insert into signals.user_genre_affinity (user_id, genre_id, score) values (?, 2, 0.9)", user.id());
        dsl.execute(
                "insert into signals.user_artist_affinity (user_id, artist_id, score) values (?, ?, 0.6)",
                user.id(),
                artist);
        dsl.execute(
                "insert into signals.user_author_affinity (user_id, author_id, score) values (?, ?, 0.8), (?, ?, 0.3)",
                user.id(),
                author.id(),
                user.id(),
                other.id());

        JsonNode profile = ok(api.get("/api/v1/admin/feed/users/" + user.id() + "/profile", api.admin()));

        assertThat(profile.path("user").path("handle").asText("")).isEqualTo(user.handle());
        assertThat(profile.path("followingCount").asInt()).isEqualTo(1);
        JsonNode genres = profile.path("genres");
        assertThat(genres.size()).isEqualTo(2);
        // Chosen genres weigh 0.6, engagement 0.4: genre 1 (chosen) leads genre 2 (engaged).
        assertThat(genres.get(0).path("id").asInt()).isEqualTo(1);
        assertThat(genres.get(0).path("chosen").asDouble()).isGreaterThan(0);
        assertThat(genres.get(0).path("name").asText("")).isNotBlank();
        assertThat(genres.get(1).path("id").asInt()).isEqualTo(2);
        assertThat(genres.get(1).path("engaged").asDouble()).isCloseTo(0.9, within(1e-6));
        assertThat(profile.path("artists").get(0).path("name").asText("")).startsWith("Inspector Band");
        assertThat(profile.path("artists").get(0).path("score").asDouble()).isCloseTo(0.6, within(1e-6));
        JsonNode authors = profile.path("authors");
        assertThat(authors.get(0).path("name").asText("")).isEqualTo(author.handle());
        assertThat(authors.get(0).path("followed").asBoolean()).isTrue();
        assertThat(authors.get(1).path("name").asText("")).isEqualTo(other.handle());
        assertThat(authors.get(1).path("followed").asBoolean()).isFalse();
    }

    @Test
    void servedSessionsComeNewestFirstWithOutcomesAndPage() {
        TestApi.User viewer = api.register("servedviewer");
        TestApi.User author = api.register("servedauthor");
        api.put("/api/v1/users/" + author.id() + "/follow", null, viewer);
        for (int i = 0; i < 3; i++) {
            api.post(
                    "/api/v1/posts",
                    Map.of("clips", List.of(Map.of("mediaId", data.video(author.id(), "READY")))),
                    author);
        }
        List<String> sessions = new ArrayList<>();
        JsonNode liked = null;
        for (int i = 0; i < 3; i++) {
            JsonNode home = ok(api.get("/api/v1/feed/home?limit=3", viewer));
            sessions.add(home.path("sessionId").asText(""));
            liked = home.path("items").get(0);
        }
        // A like on the newest session's first item, sent with that session's id.
        String newest = sessions.getLast();
        String likedPost = liked.path("post").path("id").asText("");
        ResponseEntity<JsonNode> sent = api.post(
                "/api/v1/events",
                Map.of(
                        "events",
                        List.of(Map.of(
                                "type",
                                "LIKE",
                                "postId",
                                likedPost,
                                "sessionId",
                                newest,
                                "surface",
                                "FOR_YOU",
                                "position",
                                0))),
                viewer);
        assertThat(sent.getStatusCode().is2xxSuccessful())
                .as(String.valueOf(sent.getBody()))
                .isTrue();

        JsonNode first = ok(api.get("/api/v1/admin/feed/users/" + viewer.id() + "/served?limit=2", api.admin()));

        assertThat(first.path("items").size()).isEqualTo(2);
        JsonNode top = first.path("items").get(0);
        assertThat(top.path("sessionId").asText("")).isEqualTo(newest);
        assertThat(top.path("configVersion").asInt()).isEqualTo(1);
        assertThat(top.path("shown").asInt()).isEqualTo(3);
        assertThat(top.path("positives").asInt()).isEqualTo(1);
        JsonNode entry = top.path("entries").get(0);
        assertThat(entry.path("position").asInt()).isEqualTo(1);
        assertThat(entry.path("postId").asText("")).isEqualTo(likedPost);
        assertThat(entry.path("positive").asBoolean()).isTrue();
        assertThat(entry.path("post").path("author").path("handle").asText("")).isNotBlank();
        assertThat(top.path("entries").get(1).path("positive").asBoolean()).isFalse();
        assertThat(first.path("items").get(1).path("sessionId").asText("")).isEqualTo(sessions.get(1));

        JsonNode second = ok(api.get(
                "/api/v1/admin/feed/users/" + viewer.id() + "/served?limit=2&cursor="
                        + first.path("nextCursor").asText(""),
                api.admin()));
        assertThat(second.path("items").size()).isEqualTo(1);
        assertThat(second.path("items").get(0).path("sessionId").asText("")).isEqualTo(sessions.getFirst());
        assertThat(second.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void aMissingUserIs404AndAUserCannotInspect() {
        assertThat(api.get("/api/v1/admin/feed/users/" + UUID.randomUUID() + "/profile", api.admin())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        TestApi.User user = api.register("inspnosy");
        assertThat(api.get("/api/v1/admin/feed/users/" + user.id() + "/served", user)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
