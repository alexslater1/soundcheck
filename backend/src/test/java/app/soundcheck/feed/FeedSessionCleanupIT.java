package app.soundcheck.feed;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.feed.internal.FeedSessionCleanup;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.MutableClock;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@code FeedSessionCleanup} deletes sessions past their expiry and no others. */
class FeedSessionCleanupIT extends IntegrationTest {

    @Autowired
    FeedSessionCleanup cleanup;

    @Autowired
    DSLContext dsl;

    @Autowired
    MutableClock clock;

    @Test
    void expiredSessionsAreDeletedAndLiveOnesKept() {
        UUID expired = session(Instant.now(clock).minusSeconds(1));
        UUID live = session(Instant.now(clock).plusSeconds(600));

        assertThat(cleanup.deleteExpired()).isGreaterThanOrEqualTo(1);

        assertThat(exists(expired)).isFalse();
        assertThat(exists(live)).isTrue();
        dsl.execute("delete from feed.feed_session where id = ?", live);
    }

    private UUID session(Instant expiresAt) {
        UUID id = UUID.randomUUID();
        dsl.execute(
                "insert into feed.feed_session (id, user_id, config_id, expires_at, items)"
                        + " values (?, ?, ?, ?::timestamptz, '[]'::jsonb)",
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                expiresAt.toString());
        return id;
    }

    private boolean exists(UUID id) {
        return dsl.fetchExists(dsl.selectFrom("feed.feed_session").where("id = ?", id));
    }
}
