package app.soundcheck.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.identity.internal.AccountPurgeService;
import app.soundcheck.media.ObjectStorage;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.MutableClock;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A deleted account is left alone for seven days and then removed with everything it
 * owned, in the database and in object storage, while other people's data is left consistent (their
 * counters go down by exactly what disappeared). Time is moved with the test clock.
 */
class AccountPurgeIT extends IntegrationTest {

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
    ObjectStorage storage;

    @Autowired
    AccountPurgeService purge;

    @Autowired
    MutableClock clock;

    @AfterEach
    void backToRealTime() {
        clock.reset();
    }

    @Test
    void sevenDaysAfterDeletionEverythingTheAccountOwnedIsGone(@TempDir Path dir) throws Exception {
        TestApi api = new TestApi(rest);
        TestData data = new TestData(catalogIngest, dsl, tx, events);
        TestApi.User leaver = api.register("leaver");
        TestApi.User stayer = api.register("stayer");

        // The leaver's own content: a post with a clip whose files are really in storage, and an avatar.
        UUID clip = data.video(leaver.id(), "READY");
        Path file = Files.write(dir.resolve("bytes"), new byte[] {1, 2, 3});
        String original = "uploads/" + leaver.id() + "/" + clip + "/original";
        String master = "media/" + clip + "/v1/master.m3u8";
        String segment = "media/" + clip + "/v1/720p/seg_000.m4s";
        for (String key : new String[] {original, master, segment}) {
            storage.putFile(key, file, "application/octet-stream");
        }
        String leaversPost = api.post("/api/v1/posts", Map.of("mediaId", clip), leaver)
                .getBody()
                .path("id")
                .asText("");
        api.patch("/api/v1/me", Map.of("avatarMediaId", data.image(leaver.id(), "READY")), leaver);

        // What the leaver did to other people's things, and others to theirs.
        UUID stayersClip = data.video(stayer.id(), "READY");
        storage.putFile("media/" + stayersClip + "/v1/master.m3u8", file, "application/octet-stream");
        String stayersPost = api.post("/api/v1/posts", Map.of("mediaId", stayersClip), stayer)
                .getBody()
                .path("id")
                .asText("");
        api.put("/api/v1/posts/" + stayersPost + "/like", null, leaver);
        api.post("/api/v1/posts/" + stayersPost + "/comments", Map.of("body", "from the leaver"), leaver);
        api.post("/api/v1/posts/" + stayersPost + "/comments", Map.of("body", "from the stayer"), stayer);
        api.post("/api/v1/posts/" + stayersPost + "/report", Map.of("reason", "OTHER"), leaver);
        api.put("/api/v1/posts/" + leaversPost + "/like", null, stayer);
        api.post("/api/v1/posts/" + leaversPost + "/comments", Map.of("body", "nice"), stayer);
        api.put("/api/v1/users/" + stayer.id() + "/follow", null, leaver);
        api.put("/api/v1/users/" + leaver.id() + "/follow", null, stayer);
        UUID gig = data.gig(
                "Purge night",
                data.venue("Purge venue " + UUID.randomUUID()),
                Instant.parse("2026-12-01T19:30:00Z"),
                data.artist("Purge artist " + UUID.randomUUID()));
        api.put("/api/v1/gigs/" + gig + "/attendance", Map.of("status", "GOING"), leaver);
        // The stayer's inbox now names the leaver (follow, like, comment).
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                        count("notify.notification", "user_id = ? and actor_id = ?", stayer.id(), leaver.id()))
                .isEqualTo(3));
        assertThat(counters(stayersPost)).containsExactly(1, 2);

        // Deletion is a soft delete: nothing is removed yet, and it stays that way for seven days.
        assertThat(api.call(
                                org.springframework.http.HttpMethod.DELETE,
                                "/api/v1/me",
                                Map.of("password", "correct horse battery"),
                                leaver)
                        .getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        purge.purgeDue();
        assertThat(count("identity.app_user", "id = ?", leaver.id())).isEqualTo(1);
        clock.advance(AccountPurgeService.GRACE.minusHours(1));
        purge.purgeDue();
        assertThat(count("identity.app_user", "id = ?", leaver.id())).isEqualTo(1);
        assertThat(count("content.post", "author_id = ?", leaver.id())).isEqualTo(1);
        assertThat(storage.head(master)).isPresent();

        // After the grace period.
        clock.advance(Duration.ofHours(2));
        assertThat(purge.purgeDue()).isGreaterThanOrEqualTo(1);

        // The account and everything it owned.
        assertThat(count("identity.app_user", "id = ?", leaver.id())).isZero();
        assertThat(count("content.post", "author_id = ?", leaver.id())).isZero();
        assertThat(count("content.comment", "author_id = ?", leaver.id())).isZero();
        assertThat(count("content.post_like", "user_id = ?", leaver.id())).isZero();
        assertThat(count("content.report", "reporter_id = ?", leaver.id())).isZero();
        assertThat(count("media.media_asset", "owner_id = ?", leaver.id())).isZero();
        assertThat(count("social.user_follow", "follower_id = ? or followee_id = ?", leaver.id(), leaver.id()))
                .isZero();
        assertThat(count("catalog.gig_attendance", "user_id = ?", leaver.id())).isZero();
        assertThat(count("notify.notification", "user_id = ? or actor_id = ?", leaver.id(), leaver.id()))
                .isZero();
        // …in object storage too: the original and every served file.
        assertThat(storage.head(original)).isEmpty();
        assertThat(storage.head(master)).isEmpty();
        assertThat(storage.head(segment)).isEmpty();

        // Other people's data is intact and its counters are right: one like and one comment fewer.
        assertThat(count("content.post", "id = ?", UUID.fromString(stayersPost)))
                .isEqualTo(1);
        assertThat(counters(stayersPost)).containsExactly(0, 1);
        assertThat(count("content.comment", "post_id = ?", UUID.fromString(stayersPost)))
                .isEqualTo(1);
        assertThat(storage.head("media/" + stayersClip + "/v1/master.m3u8")).isPresent();
        assertThat(count("identity.app_user", "id = ?", stayer.id())).isEqualTo(1);

        // Running it again finds nothing to do, and the email and handle are free to be used again.
        assertThat(purge.purgeDue()).isZero();
        clock.reset();
        JsonNode reRegistered = rest.postForEntity(
                        "/api/v1/auth/register",
                        new org.springframework.http.HttpEntity<>(
                                Map.of(
                                        "email",
                                        leaver.email(),
                                        "password",
                                        "correct horse battery",
                                        "handle",
                                        leaver.handle(),
                                        "displayName",
                                        "Back again"),
                                headers()),
                        JsonNode.class)
                .getBody();
        assertThat(reRegistered.path("user").path("handle").asText("")).isEqualTo(leaver.handle());
        assertThat(reRegistered.path("user").path("id").asText(""))
                .isNotEqualTo(leaver.id().toString());
    }

    private static org.springframework.http.HttpHeaders headers() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-Client", "android");
        return headers;
    }

    /** like_count and comment_count of a post. */
    private java.util.List<Integer> counters(String postId) {
        var row = dsl.fetchSingle(
                "select like_count, comment_count from content.post where id = ?", UUID.fromString(postId));
        return java.util.List.of(row.get(0, Integer.class), row.get(1, Integer.class));
    }

    private int count(String table, String where, Object... args) {
        return dsl.fetchCount(dsl.selectFrom(table).where(where, args));
    }
}
