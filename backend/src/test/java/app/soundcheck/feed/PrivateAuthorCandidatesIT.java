package app.soundcheck.feed;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.feed.internal.CandidateGenerator;
import app.soundcheck.feed.internal.CandidateRepository.Candidate;
import app.soundcheck.feed.internal.UserContextLoader;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.MutableClock;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D25b in the For You candidates, which read posts through the feed's own views, not
 * through the content module: a private profile's posts are candidates only for the people its owner
 * follows. Following a private profile gives nothing; being followed by it does.
 */
class PrivateAuthorCandidatesIT extends IntegrationTest {

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
    UserContextLoader contexts;

    @Autowired
    CandidateGenerator generator;

    @Autowired
    MutableClock clock;

    @Test
    void aPrivateAuthorsPostsReachOnlyThePeopleTheAuthorFollows() {
        TestApi api = new TestApi(rest);
        TestData data = new TestData(catalogIngest, dsl, tx, events);
        TestApi.User author = api.register("private");
        TestApi.User viewer = api.register("viewer");
        TestApi.User publicAuthor = api.register("public");
        // The viewer follows both, so both would be FOLLOWING candidates (and fresh ones FRESH).
        api.put("/api/v1/users/" + author.id() + "/follow", null, viewer);
        api.put("/api/v1/users/" + publicAuthor.id() + "/follow", null, viewer);
        UUID privatePost = post(api, data, author);
        UUID publicPost = post(api, data, publicAuthor);

        assertThat(candidateIds(viewer)).contains(privatePost, publicPost);

        api.patch("/api/v1/me", Map.of("profileVisibility", "PRIVATE"), author);
        assertThat(candidateIds(viewer)).contains(publicPost).doesNotContain(privatePost);

        // The private author follows the viewer: the viewer is in its circle.
        api.put("/api/v1/users/" + viewer.id() + "/follow", null, author);
        assertThat(candidateIds(viewer)).contains(privatePost, publicPost);
    }

    private List<UUID> candidateIds(TestApi.User viewer) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
        return generator
                .candidates(
                        contexts.load(viewer.id(), CandidateGenerationIT.PARAMS.seenWindowDays(), now),
                        CandidateGenerationIT.PARAMS,
                        now)
                .stream()
                .map(Candidate::postId)
                .toList();
    }

    private static UUID post(TestApi api, TestData data, TestApi.User author) {
        return UUID.fromString(api.post("/api/v1/posts", Map.of("mediaId", data.video(author.id(), "READY")), author)
                .getBody()
                .path("id")
                .asText(""));
    }
}
