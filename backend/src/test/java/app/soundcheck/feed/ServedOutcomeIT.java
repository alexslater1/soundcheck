package app.soundcheck.feed;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.shared.Ids;
import app.soundcheck.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code feed.served_outcome} labels each served item with what the user did with that
 * post in the same feed session. Rows are written by hand, so each case shows one rule.
 */
class ServedOutcomeIT extends IntegrationTest {

    static final UUID CONFIG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired
    DSLContext dsl;

    /** One served item: a fresh session, user and post, served a minute ago. */
    record Served(UUID session, UUID user, UUID post, Instant at) {}

    @Test
    void aLikeIsPositive() {
        Served s = serve();
        event(s, s.session(), "LIKE", null, 5);
        assertLabel(s, true, false, null);
    }

    @Test
    void watchingThreeQuartersIsPositiveButJustUnderIsNot() {
        Served under = serve();
        event(under, under.session(), "WATCH_PROGRESS", 0.74f, 5);
        assertLabel(under, false, false, 0.74);

        Served enough = serve();
        event(enough, enough.session(), "WATCH_PROGRESS", 0.5f, 5);
        event(enough, enough.session(), "WATCH_PROGRESS", 0.75f, 10);
        assertLabel(enough, true, false, 0.75);
    }

    @Test
    void aCompleteCountsAsTheWholeClipWatched() {
        Served s = serve();
        event(s, s.session(), "COMPLETE", null, 5);
        assertLabel(s, true, false, 1.0);
    }

    @Test
    void aHideWinsOverALike() {
        Served s = serve();
        event(s, s.session(), "LIKE", null, 5);
        event(s, s.session(), "HIDE", null, 10);
        assertLabel(s, false, true, null);
    }

    @Test
    void aReportIsNegative() {
        Served s = serve();
        event(s, s.session(), "REPORT", null, 5);
        assertLabel(s, false, true, null);
    }

    @Test
    void theSamePostsLikeInAnotherSessionIsNotCounted() {
        Served s = serve();
        event(s, Ids.newId(), "LIKE", null, 5);
        assertLabel(s, false, false, null);
    }

    @Test
    void anotherUsersLikeWithTheSameSessionIdIsNotCounted() {
        // The session id on an event is not checked on intake, so the user must match as well.
        Served s = serve();
        event(new Served(s.session(), Ids.newId(), s.post(), s.at()), s.session(), "LIKE", null, 5);
        assertLabel(s, false, false, null);
    }

    @Test
    void anImpressionAloneIsNeither() {
        Served s = serve();
        event(s, s.session(), "IMPRESSION", null, 1);
        event(s, s.session(), "VIEW_START", null, 2);
        assertLabel(s, false, false, null);
    }

    private Served serve() {
        Served s =
                new Served(Ids.newId(), Ids.newId(), Ids.newId(), Instant.now().minus(1, ChronoUnit.MINUTES));
        dsl.execute(
                "insert into feed.served_item (session_id, position, served_at, user_id, post_id, config_id, score,"
                        + " features, exploration) values (?, 0, ?::timestamptz, ?, ?, ?, 0.5, '{}'::real[], false)",
                s.session(),
                at(s.at()),
                s.user(),
                s.post(),
                CONFIG);
        return s;
    }

    private void event(Served s, UUID session, String type, Float value, int secondsAfterServe) {
        dsl.execute(
                "insert into signals.interaction_event (occurred_at, user_id, post_id, feed_session_id, surface,"
                        + " position, event_type, value) values (?::timestamptz, ?, ?, ?, 'FOR_YOU', 0, ?, ?)",
                at(s.at().plusSeconds(secondsAfterServe)),
                s.user(),
                s.post(),
                session,
                type,
                value);
    }

    private void assertLabel(Served s, boolean positive, boolean negative, Double maxWatch) {
        Record row = dsl.fetchSingle(
                "select positive, negative, max_watch_fraction from feed.served_outcome"
                        + " where session_id = ? and position = 0",
                s.session());
        assertThat(row.get("positive", Boolean.class)).as("positive").isEqualTo(positive);
        assertThat(row.get("negative", Boolean.class)).as("negative").isEqualTo(negative);
        Double watch = row.get("max_watch_fraction", Double.class);
        if (maxWatch == null) {
            assertThat(watch).as("max_watch_fraction").isNull();
        } else {
            assertThat(watch).as("max_watch_fraction").isCloseTo(maxWatch, org.assertj.core.data.Offset.offset(1e-6));
        }
    }

    private static String at(Instant instant) {
        return instant.toString();
    }
}
