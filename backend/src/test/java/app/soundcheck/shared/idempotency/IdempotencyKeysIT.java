package app.soundcheck.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.shared.web.ApiException;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.MutableClock;
import app.soundcheck.support.TestApi;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** The idempotency-key store: new, replay, reuse, expiry after 24 hours, purge. */
class IdempotencyKeysIT extends IntegrationTest {

    @Autowired
    IdempotencyKeys keys;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    MutableClock clock;

    @AfterEach
    void backToRealTime() {
        clock.reset();
    }

    @Test
    void newThenReplayThenReuseThenExpiry() {
        UUID user = new TestApi(rest).register("idem").id();
        String key = UUID.randomUUID().toString();

        // First use: nothing remembered; the caller does the work and records the result.
        Optional<String> first = tx.execute(status -> {
            Optional<String> remembered = keys.begin(user, key, "POST /things {\"a\":1}");
            keys.complete(user, key, 201, "{\"id\":\"t1\"}");
            return remembered;
        });
        assertThat(first).isEmpty();

        // Same key, same request → the recorded result.
        assertThat(begin(user, key, "POST /things {\"a\":1}"))
                .hasValueSatisfying(json -> assertThat(json).contains("\"t1\""));

        // Same key, different request → 409.
        assertThatThrownBy(() -> begin(user, key, "POST /things {\"a\":2}"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));

        // A first request that rolled back leaves no trace: the key can be used again.
        String rolledBack = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            keys.begin(user, rolledBack, "POST /things {}");
            status.setRollbackOnly();
        });
        assertThat(begin(user, rolledBack, "POST /things {}")).isEmpty();

        // After 24 hours the key is forgotten: a different request with it is accepted as new.
        clock.advance(Duration.ofHours(25));
        assertThat(begin(user, key, "POST /things {\"a\":2}")).isEmpty();
    }

    @Test
    void purgeForgetsOnlyOldKeysAndBeginNeedsATransaction() {
        UUID user = new TestApi(rest).register("idem").id();
        String old = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            keys.begin(user, old, "POST /a");
            keys.complete(user, old, 201, "{}");
        });
        clock.advance(Duration.ofHours(23));
        String recent = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            keys.begin(user, recent, "POST /b");
            keys.complete(user, recent, 201, "{}");
        });
        clock.advance(Duration.ofHours(2)); // old is now 25 h old, recent 2 h

        assertThat(keys.purge()).isGreaterThanOrEqualTo(1);

        assertThat(begin(user, recent, "POST /b")).isPresent();
        assertThat(begin(user, old, "POST /a")).isEmpty();
        // The key row and the work it protects must commit together, so there has to be a transaction.
        assertThatThrownBy(() -> keys.begin(user, UUID.randomUUID().toString(), "POST /c"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /** {@code begin} inside its own transaction, committed. */
    private Optional<String> begin(UUID user, String key, String request) {
        return tx.execute(status -> keys.begin(user, key, request));
    }
}
