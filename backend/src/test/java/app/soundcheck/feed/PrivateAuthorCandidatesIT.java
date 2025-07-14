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
 * Private profiles in the For You candidates, which read posts through the feed's own views, not through
 * the content module: a private profile's posts are candidates only for its followers. Being followed by
 * it gives nothing; a waiting follow request gives nothing until it is accepted.
 * This is the SQL form of social's {@code ProfileAccess}, so it must give the same answers.
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
    void aPrivateAuthorsPostsReachOnlyItsFollowers() {
        TestApi api = new TestApi(rest);
        TestData data = new TestData(catalogIngest, dsl, tx, events);
        TestApi.User author = api.register("private");
        TestApi.User follower = api.register("follower");
        TestApi.User viewer = api.register("viewer");
        TestApi.User publicAuthor = api.register("public");
        // The follower follows the author while it is public, so stays a follower.
        api.put("/api/v1/users/" + author.id() + "/follow", null, follower);
        // The viewer follows only the public author; the private author follows the viewer (which gives
        // the viewer nothing).
        api.put("/api/v1/users/" + publicAuthor.id() + "/follow", null, viewer);
        api.put("/api/v1/users/" + viewer.id() + "/follow", null, author);
        UUID privatePost = post(api, data, author);
        UUID publicPost = post(api, data, publicAuthor);

        assertThat(candidateIds(viewer)).contains(privatePost, publicPost);
        assertThat(candidateIds(follower)).contains(privatePost, publicPost);

        api.patch("/api/v1/me", Map.of("profileVisibility", "PRIVATE"), author);
        assertThat(candidateIds(viewer)).contains(publicPost).doesNotContain(privatePost);
        assertThat(candidateIds(follower)).contains(privatePost, publicPost);

        // A request is not a follow; accepted, it is.
        api.put("/api/v1/users/" + author.id() + "/follow", null, viewer);
        assertThat(candidateIds(viewer)).doesNotContain(privatePost);
        api.post("/api/v1/me/follow-requests/" + viewer.id() + "/accept", null, author);
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
