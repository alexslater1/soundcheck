package app.soundcheck.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.shared.Ids;
import app.soundcheck.shared.partitions.DailyPartitions;
import app.soundcheck.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The analytics fact views hold one row per fact with the derived columns worked out.
 * Each case is a few hand-made rows whose expected values can be checked on paper.
 */
class AnalyticsViewsIT extends IntegrationTest {

    static final Instant T = Instant.parse("2031-05-20T10:00:00Z");

    @Autowired
    DSLContext dsl;

    @Autowired
    DailyPartitions partitions;

    AnalyticsFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new AnalyticsFixture(dsl, partitions, LocalDate.parse("2031-05-20"), LocalDate.parse("2031-05-20"));
    }

    @AfterEach
    void tearDown() {
        fixture.clean();
    }

    @Test
    void serveFactCarriesTheConfigVersionAndTheLabel() {
        // Ten served items, four of them liked: 40 % positive, all served by the seed config (version 1).
        UUID user = Ids.newId();
        UUID session = fixture.session();
        for (int position = 0; position < 10; position++) {
            UUID post = Ids.newId();
            fixture.serve(session, position, T, user, post, AnalyticsFixture.SEED_CONFIG, position == 9);
            if (position < 4) {
                fixture.event(user, post, session, "LIKE", null, T.plusSeconds(5));
            }
        }

        Record r = dsl.fetchSingle(
                "select count(*) as served, count(*) filter (where positive) as positive,"
                        + " min(config_version) as low, max(config_version) as high,"
                        + " bool_and(config_activated_at is not null) as activated,"
                        + " count(*) filter (where exploration) as explored"
                        + " from analytics.serve_fact where session_id = ?",
                session);

        assertThat(r.get("served", Long.class)).isEqualTo(10);
        assertThat(r.get("positive", Long.class)).isEqualTo(4);
        assertThat((double) r.get("positive", Long.class) / r.get("served", Long.class))
                .isEqualTo(0.4);
        assertThat(r.get("low", Integer.class)).isEqualTo(1);
        assertThat(r.get("high", Integer.class)).isEqualTo(1);
        assertThat(r.get("activated", Boolean.class)).isTrue();
        assertThat(r.get("explored", Long.class)).isEqualTo(1);
        assertThat(columns("serve_fact")).doesNotContain("features");
    }

    @Test
    void postFactCountsClipsByKindAndTakesTheFirstClipByPosition() {
        UUID author = fixture.user("example.com", T);
        // A photo first, then a 42 s video, then a slot still waiting for its upload (not a clip yet).
        UUID mixed = fixture.post(author, "PUBLISHED", T, null);
        UUID photo = fixture.media(author, "IMAGE", "READY", T, 100L, null);
        UUID video = fixture.media(author, "VIDEO", "READY", T, 100L, 42_000);
        fixture.clip(mixed, author, 1, video, null); // written first, but second in the post
        fixture.clip(mixed, author, 0, photo, null);
        fixture.clip(mixed, author, 2, null, null);
        // A post whose clips never arrived.
        UUID empty = fixture.post(author, "PENDING_MEDIA", null, null);
        fixture.clip(empty, author, 0, null, null);

        Record m = postFact(mixed);
        assertThat(m.get("clips", Long.class)).isEqualTo(2);
        assertThat(m.get("videos", Long.class)).isEqualTo(1);
        assertThat(m.get("images", Long.class)).isEqualTo(1);
        assertThat(m.get("first_clip_kind", String.class)).isEqualTo("IMAGE"); // counted as a photo post
        assertThat(m.get("first_clip_duration_ms", Integer.class)).isNull();
        assertThat(m.get("published_at", Instant.class)).isEqualTo(T);

        Record e = postFact(empty);
        assertThat(e.get("clips", Long.class)).isZero();
        assertThat(e.get("first_clip_kind", String.class)).isNull();
        assertThat(e.get("published_at", Instant.class)).isNull();
    }

    @Test
    void mediaFactWorksOutTimeToReadyAndTheOutcome() {
        UUID owner = fixture.user("example.com", T);
        UUID ready = fixture.media(owner, "VIDEO", "READY", T, 1_500L, 30_000);
        UUID failed = fixture.media(owner, "VIDEO", "FAILED", T, null, null);
        UUID rejected = fixture.media(owner, "IMAGE", "REJECTED", T, null, null);
        UUID processing = fixture.media(owner, "VIDEO", "PROCESSING", T, null, null);
        UUID deletedAfterReady = fixture.media(owner, "VIDEO", "DELETED", T, 2_000L, null);

        assertThat(mediaFact(ready).get("time_to_ready_ms", Long.class)).isEqualTo(1_500);
        assertThat(mediaFact(ready).get("outcome", String.class)).isEqualTo("READY");
        assertThat(mediaFact(failed).get("outcome", String.class)).isEqualTo("FAILED");
        assertThat(mediaFact(failed).get("failure_reason", String.class)).isEqualTo("probe failed");
        assertThat(mediaFact(failed).get("time_to_ready_ms", Long.class)).isNull();
        assertThat(mediaFact(rejected).get("outcome", String.class)).isEqualTo("FAILED");
        assertThat(mediaFact(processing).get("outcome", String.class)).isNull();
        assertThat(mediaFact(deletedAfterReady).get("outcome", String.class)).isEqualTo("READY");
        assertThat(mediaFact(deletedAfterReady).get("time_to_ready_ms", Long.class))
                .isEqualTo(2_000);
    }

    @Test
    void userFactKeepsTheEmailDomainOnlyInLowerCase() {
        UUID persona = fixture.user("Seed.Soundcheck.TEST", T);
        UUID fan = fixture.user("Example.com", T.plusSeconds(60));

        Record p = dsl.fetchSingle("select * from analytics.user_fact where user_id = ?", persona);
        assertThat(p.get("email_domain", String.class)).isEqualTo(AnalyticsFixture.SEED_DOMAIN);
        assertThat(p.get("created_at", Instant.class)).isEqualTo(T);
        assertThat(p.get("deleted_at", Instant.class)).isNull();
        assertThat(dsl.fetchValue("select email_domain from analytics.user_fact where user_id = ?", fan))
                .isEqualTo("example.com");
        assertThat(columns("user_fact")).containsExactly("user_id", "created_at", "deleted_at", "email_domain");
    }

    @Test
    void aSyntheticDomainMatchesInSqlAsInJava() {
        UUID persona = fixture.user(AnalyticsFixture.SIM_DOMAIN, T);
        UUID fan = fixture.user("example.com", T);
        String[] domains = {AnalyticsFixture.SEED_DOMAIN, AnalyticsFixture.SIM_DOMAIN};

        assertThat(dsl.fetchValue(
                        "select count(*) from analytics.user_fact where user_id in (?, ?) and email_domain = any(?)",
                        persona,
                        fan,
                        domains))
                .isEqualTo(1L);
    }

    private Record postFact(UUID post) {
        return dsl.fetchSingle("select * from analytics.post_fact where post_id = ?", post);
    }

    private Record mediaFact(UUID media) {
        return dsl.fetchSingle("select * from analytics.media_fact where media_id = ?", media);
    }

    private List<String> columns(String view) {
        return dsl.fetch(
                        "select column_name from information_schema.columns where table_schema = 'analytics'"
                                + " and table_name = ? order by ordinal_position",
                        view)
                .getValues(0, String.class);
    }
}
