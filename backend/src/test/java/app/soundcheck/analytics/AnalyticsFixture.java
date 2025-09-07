package app.soundcheck.analytics;

import app.soundcheck.shared.Ids;
import app.soundcheck.shared.partitions.DailyPartitions;
import app.soundcheck.shared.partitions.PartitionedTable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;

/**
 * Hand-made rows for the analytics tests, written straight into the tables with the exact
 * timestamps each case needs, in days far ahead (2031) that no other test writes to. Everything it creates
 * is deleted again by {@link #clean()}: the posts are published with READY clips, so other tests' feeds
 * would otherwise pick them up.
 */
final class AnalyticsFixture {

    static final UUID SEED_CONFIG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    static final String SEED_DOMAIN = "seed.soundcheck.test";
    static final String SIM_DOMAIN = "sim.soundcheck.test";

    private final DSLContext dsl;
    private final List<UUID> users = new ArrayList<>();
    private final List<UUID> media = new ArrayList<>();
    private final List<UUID> posts = new ArrayList<>();
    private final List<UUID> sessions = new ArrayList<>();
    private final List<UUID> configs = new ArrayList<>();
    private final List<UUID> gigs = new ArrayList<>();
    private final List<UUID> venues = new ArrayList<>();
    private final List<UUID> artists = new ArrayList<>();
    private final List<UUID> eventUsers = new ArrayList<>();

    AnalyticsFixture(DSLContext dsl, DailyPartitions partitions, LocalDate firstDay, LocalDate lastDay) {
        this.dsl = dsl;
        partitions.ensure(new PartitionedTable("signals", "interaction_event", 90), firstDay, lastDay);
        partitions.ensure(new PartitionedTable("feed", "served_item", 30), firstDay, lastDay);
    }

    /** An account at {@code domain}, created at {@code createdAt}. */
    UUID user(String domain, Instant createdAt) {
        UUID id = Ids.newId();
        String handle = "an" + id.toString().replace("-", "").substring(20);
        dsl.execute(
                "insert into identity.app_user (id, email, handle, display_name, password_hash, created_at, updated_at)"
                        + " values (?, ?, ?, 'Analytics test', 'x', ?::timestamptz, ?::timestamptz)",
                id,
                handle + "@" + domain,
                handle,
                createdAt.toString(),
                createdAt.toString());
        users.add(id);
        return id;
    }

    /**
     * An asset uploaded at {@code uploadedAt}; READY ones are ready {@code readyAfterMs} later (null leaves it
     * not ready).
     */
    UUID media(
            UUID owner,
            String kind,
            String status,
            @Nullable Instant uploadedAt,
            @Nullable Long readyAfterMs,
            @Nullable Integer durationMs) {
        UUID id = Ids.newId();
        Instant readyAt = uploadedAt == null || readyAfterMs == null ? null : uploadedAt.plusMillis(readyAfterMs);
        dsl.execute(
                "insert into media.media_asset (id, owner_id, kind, status, content_type, declared_bytes, original_key,"
                        + " upload_strategy, uploaded_at, ready_at, duration_ms, failure_reason)"
                        + " values (?, ?, ?, ?, ?, 1000, ?, 'SINGLE', ?::timestamptz, ?::timestamptz, ?, ?)",
                id,
                owner,
                kind,
                status,
                kind.equals("VIDEO") ? "video/mp4" : "image/png",
                "uploads/" + owner + "/" + id + "/original",
                uploadedAt == null ? null : uploadedAt.toString(),
                readyAt == null ? null : readyAt.toString(),
                durationMs,
                status.equals("FAILED") ? "probe failed" : null);
        media.add(id);
        return id;
    }

    /** A post published at {@code publishedAt} (null: never published), with no clips yet. */
    UUID post(UUID author, String status, @Nullable Instant publishedAt, @Nullable UUID gig) {
        UUID id = Ids.newId();
        dsl.execute(
                "insert into content.post (id, author_id, gig_id, status, published_at) values (?, ?, ?, ?, ?::timestamptz)",
                id,
                author,
                gig,
                status,
                publishedAt == null ? null : publishedAt.toString());
        posts.add(id);
        return id;
    }

    /** A clip slot of the post; {@code mediaId} null is a slot still waiting for its upload. */
    void clip(UUID post, UUID owner, int position, @Nullable UUID mediaId, @Nullable UUID artist) {
        dsl.execute(
                "insert into content.post_clip (id, post_id, owner_id, position, artist_id, media_id)"
                        + " values (?, ?, ?, ?, ?, ?)",
                Ids.newId(),
                post,
                owner,
                position,
                artist,
                mediaId);
    }

    /** A ranking config (ARCHIVED) with a version no other test uses, activated at {@code activatedAt}. */
    UUID config(int version, Instant activatedAt) {
        UUID id = Ids.newId();
        dsl.execute(
                "insert into feed.ranking_config (id, version, status, feature_schema_version, weights, params, source,"
                        + " activated_at) values (?, ?, 'ARCHIVED', 1, '{}'::jsonb, '{}'::jsonb, 'MANUAL', ?::timestamptz)",
                id,
                version,
                activatedAt.toString());
        configs.add(id);
        return id;
    }

    UUID session() {
        UUID id = Ids.newId();
        sessions.add(id);
        return id;
    }

    void serve(UUID session, int position, Instant servedAt, UUID user, UUID post, UUID config, boolean exploration) {
        dsl.execute(
                "insert into feed.served_item (session_id, position, served_at, user_id, post_id, config_id, score,"
                        + " features, exploration) values (?, ?, ?::timestamptz, ?, ?, ?, 0.5, '{}'::real[], ?)",
                session,
                position,
                servedAt.toString(),
                user,
                post,
                config,
                exploration);
    }

    /** An event received at {@code at}; {@code session} null for one outside a feed. */
    void event(UUID user, UUID post, @Nullable UUID session, String type, @Nullable Float value, Instant at) {
        dsl.execute(
                "insert into signals.interaction_event (occurred_at, user_id, post_id, feed_session_id, surface,"
                        + " position, event_type, value) values (?::timestamptz, ?, ?, ?, 'FOR_YOU', 0, ?, ?)",
                at.toString(),
                user,
                post,
                session,
                type,
                value);
        eventUsers.add(user);
    }

    UUID artist(String name) {
        UUID id = Ids.newId();
        dsl.execute("insert into catalog.artist (id, name, name_norm) values (?, ?, lower(?))", id, name, name);
        artists.add(id);
        return id;
    }

    /** A gig at a venue of its own (no location, so no geographic search finds it), long past. */
    UUID gig(String title) {
        UUID venue = Ids.newId();
        dsl.execute(
                "insert into catalog.venue (id, name, name_norm) values (?, ?, lower(?))",
                venue,
                title + " Hall",
                title + " Hall");
        venues.add(venue);
        UUID id = Ids.newId();
        dsl.execute(
                "insert into catalog.gig (id, title, venue_id, starts_at, content_hash)"
                        + " values (?, ?, ?, '2021-06-01T19:00:00Z', ?)",
                id,
                title,
                venue,
                id.toString().getBytes());
        gigs.add(id);
        return id;
    }

    /** Deletes everything this fixture wrote, dependants first. */
    void clean() {
        for (UUID user : eventUsers) {
            dsl.execute("delete from signals.interaction_event where user_id = ?", user);
        }
        for (UUID session : sessions) {
            dsl.execute("delete from feed.served_item where session_id = ?", session);
        }
        for (UUID config : configs) {
            dsl.execute("delete from feed.ranking_config where id = ?", config);
        }
        for (UUID post : posts) {
            dsl.execute("delete from content.post where id = ?", post); // its clips cascade
        }
        for (UUID id : media) {
            dsl.execute("delete from media.media_asset where id = ?", id);
        }
        for (UUID gig : gigs) {
            dsl.execute("delete from catalog.gig where id = ?", gig);
        }
        for (UUID venue : venues) {
            dsl.execute("delete from catalog.venue where id = ?", venue);
        }
        for (UUID artist : artists) {
            dsl.execute("delete from catalog.artist where id = ?", artist);
        }
        for (UUID user : users) {
            dsl.execute("delete from identity.app_user where id = ?", user);
        }
    }
}
