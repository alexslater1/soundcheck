package app.soundcheck.content;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V096 against real PostGIS, on a database of its own: posts made before it, one clip each, become
 * one-clip posts, and the live or pending posts one author made from one gig are merged into the oldest:
 * it takes their clips in order, their likes (each person once), comments, hides, acts and the first
 * caption written, and the others are soft-deleted. Deleted, failed and gig-less posts are left alone.
 */
class PostClipsMigrationIT {

    @Test
    void existingPostsBecomeClipsAndOneAuthorsPostsFromOneGigAreMerged() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse(IntegrationTest.POSTGIS_IMAGE).asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            // The versions from the file names, so the test survives a renumbering at merge time.
            List<Integer> versions = versions();
            int clips = versions.stream()
                    .filter(v -> exists("V%03d__content_post_clips.sql".formatted(v)))
                    .findFirst()
                    .orElseThrow();
            int previous = versions.stream()
                    .filter(v -> v < clips)
                    .max(Integer::compare)
                    .orElseThrow();
            Flyway before = flyway(postgres, String.valueOf(previous));
            before.migrate();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                DSLContext db = DSL.using(connection, SQLDialect.POSTGRES);

                UUID fan = user(db, "fan");
                UUID other = user(db, "other");
                UUID liker = user(db, "liker");
                UUID commenter = user(db, "commenter");
                UUID venue = UUID.randomUUID();
                db.execute("insert into catalog.venue (id, name, name_norm) values (?, 'Scala', 'scala')", venue);
                UUID gig = gig(db, venue, "2026-08-01T19:30:00Z");
                UUID otherGig = gig(db, venue, "2026-08-08T19:30:00Z");
                UUID act = UUID.randomUUID();
                UUID support = UUID.randomUUID();
                db.execute("insert into catalog.artist (id, name, name_norm) values (?, 'Keo', 'keo')", act);
                db.execute("insert into catalog.artist (id, name, name_norm) values (?, 'Cards', 'cards')", support);

                UUID deleted = post(db, fan, gig, null, "DELETED", "2026-08-01T21:00:00Z");
                UUID first = post(db, fan, gig, null, "PUBLISHED", "2026-08-01T22:00:00Z");
                UUID second = post(db, fan, gig, "Encore!", "PUBLISHED", "2026-08-01T22:05:00Z");
                UUID third = post(db, fan, gig, "Later", "PENDING_MEDIA", "2026-08-01T22:10:00Z");
                UUID failed = post(db, fan, gig, null, "FAILED", "2026-08-01T22:20:00Z");
                UUID elsewhere = post(db, fan, otherGig, null, "PUBLISHED", "2026-08-08T22:00:00Z");
                UUID noGig = post(db, fan, null, null, "PUBLISHED", "2026-08-02T10:00:00Z");
                UUID othersPost = post(db, other, gig, null, "PUBLISHED", "2026-08-01T22:01:00Z");
                db.execute("insert into content.post_artist (post_id, artist_id) values (?, ?)", first, act);
                db.execute("insert into content.post_artist (post_id, artist_id) values (?, ?)", second, support);
                db.execute("insert into content.post_like (user_id, post_id) values (?, ?)", liker, first);
                db.execute("insert into content.post_like (user_id, post_id) values (?, ?)", liker, second);
                db.execute("insert into content.post_like (user_id, post_id) values (?, ?)", commenter, second);
                db.execute(
                        "insert into content.comment (id, post_id, author_id, body) values (?, ?, ?, 'great')",
                        UUID.randomUUID(),
                        second,
                        commenter);
                db.execute("insert into content.post_hide (user_id, post_id) values (?, ?)", other, third);

                flyway(postgres, String.valueOf(clips)).migrate();

                // The oldest live post takes the others' clips, in the order they were posted.
                assertThat(clipOwners(db, first)).hasSize(3);
                assertThat(db.fetch(
                                        "select o.id from content.post_clip c join content.post o on o.media_id = c.media_id"
                                                + " where c.post_id = ? order by c.position",
                                        first)
                                .getValues(0, UUID.class))
                        .containsExactly(first, second, third);
                // The only act each had is its clip's act.
                assertThat(db.fetch(
                                        "select artist_id from content.post_clip where post_id = ? order by position",
                                        first)
                                .getValues(0, UUID.class))
                        .containsExactly(act, support, null);
                var kept = db.fetchOne(
                        "select status, caption, like_count, comment_count, day::text from content.post where id = ?",
                        first);
                assertThat(kept.get("status", String.class)).isEqualTo("PUBLISHED");
                assertThat(kept.get("caption", String.class)).isEqualTo("Encore!");
                assertThat(kept.get("like_count", Integer.class))
                        .as("the liker counted once")
                        .isEqualTo(2);
                assertThat(kept.get("comment_count", Integer.class)).isEqualTo(1);
                assertThat(kept.get("day", String.class)).isEqualTo("2026-08-01");
                assertThat(db.fetchCount(db.selectFrom("content.comment").where("post_id = ?", first)))
                        .isEqualTo(1);
                assertThat(db.fetchCount(db.selectFrom("content.post_hide").where("post_id = ?", first)))
                        .isEqualTo(1);
                assertThat(db.fetch("select artist_id from content.post_artist where post_id = ?", first)
                                .getValues(0, UUID.class))
                        .containsExactlyInAnyOrder(act, support);
                assertThat(status(db, second)).isEqualTo("DELETED");
                assertThat(status(db, third)).isEqualTo("DELETED");

                // Left alone: a deleted and a failed post, another gig, no gig, another fan.
                for (UUID alone : List.of(deleted, failed, elsewhere, noGig, othersPost)) {
                    assertThat(clipOwners(db, alone)).as(alone.toString()).hasSize(1);
                }
                assertThat(status(db, deleted)).isEqualTo("DELETED");
                assertThat(status(db, failed)).isEqualTo("FAILED");
                assertThat(status(db, othersPost)).isEqualTo("PUBLISHED");

                // From now on one author has one open post per gig and day.
                assertThat(db.fetchCount(db.selectFrom("pg_indexes").where("indexname = 'post_author_gig_day'")))
                        .isEqualTo(1);
                // The feed's candidates are the live posts with a playable clip: one per author and gig.
                assertThat(db.fetch("select post_id from feed.post_candidate").getValues(0, UUID.class))
                        .containsExactlyInAnyOrder(first, elsewhere, noGig, othersPost);
            }
        }
    }

    private static List<Integer> versions() throws IOException {
        return Arrays.stream(new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql"))
                .map(r -> Integer.parseInt(
                        r.getFilename().substring(1, r.getFilename().indexOf("__"))))
                .sorted()
                .toList();
    }

    private static boolean exists(String file) {
        return new ClassPathResource("db/migration/" + file).exists();
    }

    private static Flyway flyway(PostgreSQLContainer postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static UUID user(DSLContext db, String handle) {
        UUID id = UUID.randomUUID();
        db.execute(
                "insert into identity.app_user (id, email, handle, display_name, password_hash) values (?, ?, ?, ?, 'x')",
                id,
                handle + "@test.local",
                handle,
                handle);
        return id;
    }

    private static UUID gig(DSLContext db, UUID venue, String startsAt) {
        UUID id = UUID.randomUUID();
        db.execute(
                "insert into catalog.gig (id, title, venue_id, starts_at, content_hash)"
                        + " values (?, 'Night', ?, ?::timestamptz, '\\x00')",
                id,
                venue,
                startsAt);
        return id;
    }

    private static UUID post(DSLContext db, UUID author, UUID gig, String caption, String status, String at) {
        UUID media = UUID.randomUUID();
        db.execute(
                "insert into media.media_asset (id, owner_id, kind, status, content_type, declared_bytes, original_key,"
                        + " upload_strategy) values (?, ?, 'VIDEO', 'READY', 'video/mp4', 1, 'k', 'SINGLE')",
                media,
                author);
        UUID id = UUID.randomUUID();
        db.execute(
                "insert into content.post (id, author_id, media_id, gig_id, caption, status, created_at, published_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?::timestamptz, case when ? = 'PUBLISHED' then ?::timestamptz end)",
                id,
                author,
                media,
                gig,
                caption,
                status,
                at,
                status,
                at);
        return id;
    }

    private static List<UUID> clipOwners(DSLContext db, UUID postId) {
        return db.fetch("select owner_id from content.post_clip where post_id = ?", postId)
                .getValues(0, UUID.class);
    }

    private static String status(DSLContext db, UUID postId) {
        return db.fetchValue("select status from content.post where id = ?", postId)
                .toString();
    }
}
