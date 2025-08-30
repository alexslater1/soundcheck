package app.soundcheck.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.soundcheck.content.PostLiked;
import app.soundcheck.notifications.internal.push.PushGateway;
import app.soundcheck.notifications.internal.push.PushMessage;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Push notifications end to end, with FCM replaced by a recorder: a phone registers its token, a like writes an
 * inbox row, and once that commits the phone is sent the Activity sentence. Collapsed likes, sign-out
 * and gone tokens send nothing more.
 */
@Import(PushIT.Recorder.class)
class PushIT extends IntegrationTest {

    record Sent(String token, PushMessage message) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class Recorder {

        static final List<Sent> SENT = new CopyOnWriteArrayList<>();
        static final Set<String> GONE = ConcurrentHashMap.newKeySet();

        @Bean
        @Primary
        PushGateway recordingGateway() {
            return (token, message) -> {
                SENT.add(new Sent(token, message));
                return GONE.contains(token) ? PushGateway.Outcome.TOKEN_GONE : PushGateway.Outcome.SENT;
            };
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    DSLContext dsl;

    TestApi api;
    TestApi.User author;
    TestApi.User fan;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        author = api.register("pushauthor");
        fan = api.register("pushfan");
        Recorder.SENT.clear();
        Recorder.GONE.clear();
    }

    @Test
    void aLikePushesTheActivitySentenceToTheAuthorsPhoneOnce() {
        String phone = "token-" + UUID.randomUUID();
        assertThat(register(author, phone, "android")).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(register(author, phone, "android")).isEqualTo(HttpStatus.NO_CONTENT); // again: harmless

        UUID postId = UUID.randomUUID();
        like(postId);
        await().atMost(Duration.ofSeconds(10)).until(() -> sentTo(phone).size() == 1);
        PushMessage message = sentTo(phone).getFirst().message();
        assertThat(message.body()).isEqualTo("@" + fan.handle() + " liked your clip");
        assertThat(message.data())
                .containsEntry("type", "POST_LIKED")
                .containsEntry("postId", postId.toString())
                .containsKey("notificationId");

        // A second like while the first is unread is collapsed into it: no new row, no new push.
        like(postId);
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(5))
                .until(() -> sentTo(phone).size() == 1);
    }

    @Test
    void signingOutStopsThePushesAndOnlyTheOwnerCanRemoveAToken() {
        String phone = "token-" + UUID.randomUUID();
        register(author, phone, "android");

        // Someone else cannot unregister the author's phone.
        assertThat(remove(fan, phone)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(tokensOf(author)).containsExactly(phone);

        assertThat(remove(author, phone)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(tokensOf(author)).isEmpty();
        like(UUID.randomUUID());
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> Recorder.SENT.isEmpty());
    }

    @Test
    void aTokenFcmCallsGoneIsDeletedAndAPhoneThatChangesAccountMovesWithIt() {
        String shared = "token-" + UUID.randomUUID();
        register(fan, shared, "android");
        register(author, shared, "android"); // the same phone, now signed in as the author
        assertThat(tokensOf(fan)).isEmpty();
        assertThat(tokensOf(author)).containsExactly(shared);

        Recorder.GONE.add(shared);
        like(UUID.randomUUID());
        await().atMost(Duration.ofSeconds(10)).until(() -> tokensOf(author).isEmpty());
    }

    @Test
    void deletingTheAccountStopsItsPushesAtOnce() {
        String phone = "token-" + UUID.randomUUID();
        register(author, phone, "android");

        // DELETE /me revokes the sessions now and purges the user row days later; the phone goes now.
        api.call(HttpMethod.DELETE, "/api/v1/me", Map.of("password", "correct horse battery"), author);
        await().atMost(Duration.ofSeconds(10)).until(() -> tokensOf(author).isEmpty());
    }

    @Test
    void onlyAndroidTokensAreTaken() {
        assertThat(register(author, "token-x", "ios")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(register(author, "", "android")).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private void like(UUID postId) {
        tx.executeWithoutResult(status -> events.publishEvent(new PostLiked(postId, author.id(), fan.id())));
    }

    private List<Sent> sentTo(String token) {
        return Recorder.SENT.stream().filter(sent -> sent.token().equals(token)).toList();
    }

    private List<String> tokensOf(TestApi.User user) {
        return dsl.fetch("select token from notify.push_token where user_id = ?", user.id())
                .getValues(0, String.class);
    }

    private HttpStatus register(TestApi.User user, String token, String platform) {
        return HttpStatus.valueOf(api.call(
                        HttpMethod.PUT,
                        "/api/v1/notifications/push-token",
                        Map.of("token", token, "platform", platform),
                        user)
                .getStatusCode()
                .value());
    }

    private HttpStatus remove(TestApi.User user, String token) {
        return HttpStatus.valueOf(
                api.call(HttpMethod.DELETE, "/api/v1/notifications/push-token", Map.of("token", token), user)
                        .getStatusCode()
                        .value());
    }
}
