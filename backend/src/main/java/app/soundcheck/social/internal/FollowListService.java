package app.soundcheck.social.internal;

import static app.soundcheck.shared.jooq.social.Tables.ARTIST_FOLLOW;
import static app.soundcheck.shared.jooq.social.Tables.VENUE_FOLLOW;

import app.soundcheck.catalog.ArtistSummary;
import app.soundcheck.catalog.CatalogViews;
import app.soundcheck.catalog.VenueSummary;
import app.soundcheck.shared.web.Cursor;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The artists and venues a user follows, for the profile's Artists tab. Open to whoever may see the
 * user's follower lists. Each row says whether the viewer follows it too; on one's own profile
 * it also carries the follow's notification level, which nobody else sees.
 */
@Service
@Transactional(readOnly = true)
public class FollowListService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    public record FollowedArtist(
            ArtistSummary artist,
            @Schema(
                            nullable = true,
                            allowableValues = {"ALL", "POSTS", "GIGS", "NONE"},
                            description = "The owner's level for this follow; only on one's own profile")
                    String notifyLevel) {}

    public record FollowedVenue(
            VenueSummary venue,
            /** The viewer follows this venue too. */
            boolean isFollowing,
            @Schema(
                            nullable = true,
                            allowableValues = {"ALL", "POSTS", "GIGS", "NONE"},
                            description = "The owner's level for this follow; only on one's own profile")
                    String notifyLevel) {}

    /** One follow row: what is followed, since when, at which level. */
    private record Edge(UUID id, Instant since, String level) {}

    private final DSLContext dsl;
    private final UserGraphService graph;
    private final CatalogViews catalog;

    FollowListService(DSLContext dsl, UserGraphService graph, CatalogViews catalog) {
        this.dsl = dsl;
        this.graph = graph;
        this.catalog = catalog;
    }

    /** Newest follow first. Expected index: artist_follow_user (user_id, created_at desc, artist_id desc). */
    public Page<FollowedArtist> artists(UUID viewerId, UUID userId, @Nullable String cursor, @Nullable Integer limit) {
        graph.listsOpenTo(viewerId, userId);
        int size = limit(limit);
        Cursor after = Cursor.decode(cursor);
        Condition where = ARTIST_FOLLOW.USER_ID.eq(userId);
        if (after != null) {
            where = where.and(DSL.row(ARTIST_FOLLOW.CREATED_AT, ARTIST_FOLLOW.ARTIST_ID)
                    .lt(DSL.row(after.at().atOffset(ZoneOffset.UTC), after.id())));
        }
        List<Edge> edges = dsl.select(ARTIST_FOLLOW.ARTIST_ID, ARTIST_FOLLOW.CREATED_AT, ARTIST_FOLLOW.NOTIFY)
                .from(ARTIST_FOLLOW)
                .where(where)
                .orderBy(ARTIST_FOLLOW.CREATED_AT.desc(), ARTIST_FOLLOW.ARTIST_ID.desc())
                .limit(size + 1)
                .fetch(r -> new Edge(r.value1(), r.value2().toInstant(), r.value3()));
        List<Edge> shown = edges.size() > size ? edges.subList(0, size) : edges;
        Map<UUID, ArtistSummary> artists =
                catalog.artists(viewerId, shown.stream().map(Edge::id).toList());
        boolean own = viewerId.equals(userId);
        List<FollowedArtist> items = shown.stream()
                .filter(e -> artists.containsKey(e.id()))
                .map(e -> new FollowedArtist(artists.get(e.id()), own ? e.level() : null))
                .toList();
        return new Page<>(items, next(edges, shown, size));
    }

    /** Newest follow first. Expected index: venue_follow_user (user_id, created_at desc, venue_id desc). */
    public Page<FollowedVenue> venues(UUID viewerId, UUID userId, @Nullable String cursor, @Nullable Integer limit) {
        graph.listsOpenTo(viewerId, userId);
        int size = limit(limit);
        Cursor after = Cursor.decode(cursor);
        Condition where = VENUE_FOLLOW.USER_ID.eq(userId);
        if (after != null) {
            where = where.and(DSL.row(VENUE_FOLLOW.CREATED_AT, VENUE_FOLLOW.VENUE_ID)
                    .lt(DSL.row(after.at().atOffset(ZoneOffset.UTC), after.id())));
        }
        List<Edge> edges = dsl.select(VENUE_FOLLOW.VENUE_ID, VENUE_FOLLOW.CREATED_AT, VENUE_FOLLOW.NOTIFY)
                .from(VENUE_FOLLOW)
                .where(where)
                .orderBy(VENUE_FOLLOW.CREATED_AT.desc(), VENUE_FOLLOW.VENUE_ID.desc())
                .limit(size + 1)
                .fetch(r -> new Edge(r.value1(), r.value2().toInstant(), r.value3()));
        List<Edge> shown = edges.size() > size ? edges.subList(0, size) : edges;
        List<UUID> ids = shown.stream().map(Edge::id).toList();
        Map<UUID, VenueSummary> venues = catalog.venues(ids);
        boolean own = viewerId.equals(userId);
        // On one's own profile every row is followed; otherwise ask once for the page (primary key).
        Set<UUID> viewerFollows = own
                ? Set.copyOf(ids)
                : Set.copyOf(dsl.select(VENUE_FOLLOW.VENUE_ID)
                        .from(VENUE_FOLLOW)
                        .where(VENUE_FOLLOW.USER_ID.eq(viewerId))
                        .and(VENUE_FOLLOW.VENUE_ID.in(ids))
                        .fetch(VENUE_FOLLOW.VENUE_ID));
        List<FollowedVenue> items = shown.stream()
                .map(e -> {
                    VenueSummary venue = venues.get(e.id());
                    return venue == null
                            ? null
                            : new FollowedVenue(venue, viewerFollows.contains(e.id()), own ? e.level() : null);
                })
                .filter(Objects::nonNull)
                .toList();
        return new Page<>(items, next(edges, shown, size));
    }

    private static @Nullable String next(List<Edge> edges, List<Edge> shown, int size) {
        return edges.size() > size
                ? new Cursor(shown.getLast().since(), shown.getLast().id()).encode()
                : null;
    }

    private static int limit(@Nullable Integer limit) {
        return limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
