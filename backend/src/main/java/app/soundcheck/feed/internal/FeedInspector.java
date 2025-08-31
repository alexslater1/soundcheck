package app.soundcheck.feed.internal;

import static app.soundcheck.shared.jooq.feed.Tables.RANKING_CONFIG;
import static app.soundcheck.shared.jooq.feed.Tables.SERVED_ITEM;
import static app.soundcheck.shared.jooq.feed.Tables.SERVED_OUTCOME;

import app.soundcheck.catalog.ArtistSummary;
import app.soundcheck.catalog.CatalogViews;
import app.soundcheck.catalog.Genre;
import app.soundcheck.catalog.ReferenceData;
import app.soundcheck.content.Post;
import app.soundcheck.content.PostFeed;
import app.soundcheck.feed.internal.ranking.GenreProfile;
import app.soundcheck.identity.UserSummary;
import app.soundcheck.identity.Users;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Cursor;
import app.soundcheck.shared.web.Page;
import app.soundcheck.signals.Affinities;
import app.soundcheck.social.SocialGraph;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record3;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The console's feed inspector: what ranking knows about one user (their taste and affinities),
 * and the feed sessions they were actually shown, item by item, with what they did with each. What they
 * would see now is the preview with the active config ({@link RankingPreview}).
 */
@Service
@ConditionalOnRole(Roles.API)
@Transactional(readOnly = true)
public class FeedInspector {

    static final int TOP_GENRES = 10;
    static final int TOP_AFFINITIES = 15;
    static final int MAX_SESSIONS = 20;

    /**
     * What ranking knows about a user.
     *
     * @param genres the blended taste vector, strongest first, with the two parts it blends
     */
    @Schema(name = "FeedProfile")
    public record Profile(
            UserSummary user,
            List<GenreWeight> genres,
            List<Affinity> artists,
            List<Affinity> authors,
            int followingCount,
            int followedArtistCount) {}

    /**
     * @param weight  the blended weight ranking uses (chosen 0.6, engagement 0.4, then length 1)
     * @param chosen  the weight from the genres the user picked, or 0
     * @param engaged the weight from what they engaged with, or 0
     */
    @Schema(name = "FeedGenreWeight")
    public record GenreWeight(short id, String name, double weight, double chosen, double engaged) {}

    /** An artist or an author the user engages with; {@code name} is an author's handle. */
    @Schema(name = "FeedAffinity")
    public record Affinity(UUID id, String name, double score, boolean followed) {}

    /**
     * One feed session as it was served.
     *
     * @param shown     how many items the user was served (the pages they reached)
     * @param positives items with a positive outcome
     */
    @Schema(name = "ServedSession")
    public record ServedSession(
            UUID sessionId,
            Instant startedAt,
            UUID configId,
            @Schema(nullable = true) Integer configVersion,
            int shown,
            int positives,
            int negatives,
            List<ServedEntry> entries) {}

    /**
     * One served item and its outcome.
     *
     * @param position 1 for the top of the session
     * @param post     null when the post has since gone, or the user can no longer see it
     */
    @Schema(name = "ServedEntry")
    public record ServedEntry(
            int position,
            UUID postId,
            @Schema(nullable = true) Post post,
            double score,
            boolean exploration,
            boolean positive,
            boolean negative,
            @Schema(nullable = true) Double maxWatchFraction) {}

    private final Users users;
    private final ReferenceData reference;
    private final CatalogViews catalog;
    private final Affinities affinities;
    private final SocialGraph social;
    private final PostFeed posts;
    private final DSLContext dsl;

    FeedInspector(
            Users users,
            ReferenceData reference,
            CatalogViews catalog,
            Affinities affinities,
            SocialGraph social,
            PostFeed posts,
            DSLContext dsl) {
        this.users = users;
        this.reference = reference;
        this.catalog = catalog;
        this.affinities = affinities;
        this.social = social;
        this.posts = posts;
        this.dsl = dsl;
    }

    public Profile profile(UUID userId) {
        UserSummary user = user(userId);
        Map<Short, Double> chosen = users.genrePreferences(userId);
        Map<Short, Double> engaged = affinities.genres(userId);
        Map<Short, Double> blended = GenreProfile.blend(chosen, engaged);
        Map<Short, String> names =
                reference.genres().stream().collect(Collectors.toMap(Genre::id, Genre::name, (x, y) -> x));
        List<GenreWeight> genres = GenreProfile.top(blended, TOP_GENRES).stream()
                .map(id -> new GenreWeight(
                        id,
                        names.getOrDefault(id, "genre " + id),
                        blended.get(id),
                        chosen.getOrDefault(id, 0.0),
                        engaged.getOrDefault(id, 0.0)))
                .toList();

        Set<UUID> followedArtists = social.followedArtistIds(userId);
        Map<UUID, Double> artistScores = affinities.artists(userId, TOP_AFFINITIES);
        Map<UUID, ArtistSummary> artists = catalog.artists(userId, artistScores.keySet());
        List<Affinity> topArtists = strongest(artistScores).stream()
                .map(e -> new Affinity(
                        e.getKey(),
                        artists.containsKey(e.getKey())
                                ? artists.get(e.getKey()).name()
                                : e.getKey().toString(),
                        e.getValue(),
                        followedArtists.contains(e.getKey())))
                .toList();

        Set<UUID> following = social.followeeIds(userId);
        Map<UUID, Double> authorScores = affinities.authors(userId, TOP_AFFINITIES);
        Map<UUID, UserSummary> authors = users.summaries(authorScores.keySet());
        List<Affinity> topAuthors = strongest(authorScores).stream()
                .filter(e -> authors.containsKey(e.getKey())) // deleted accounts drop out
                .map(e -> new Affinity(
                        e.getKey(), authors.get(e.getKey()).handle(), e.getValue(), following.contains(e.getKey())))
                .toList();

        return new Profile(user, genres, topArtists, topAuthors, following.size(), followedArtists.size());
    }

    /**
     * The user's feed sessions, newest first, a page of sessions at a time (keyset on the session's first
     * serve). Served items are kept 30 days, so that is as far back as it goes.
     */
    public Page<ServedSession> served(UUID userId, @Nullable String cursor, @Nullable Integer limit) {
        user(userId);
        int size = Math.max(1, Math.min(limit == null ? 10 : limit, MAX_SESSIONS));
        Cursor after = Cursor.decode(cursor);

        Field<OffsetDateTime> started = DSL.min(SERVED_ITEM.SERVED_AT).as("started");
        // Expected index: served_item_user (user_id, served_at) on each partition; one user's 30 days of rows.
        List<Record3<UUID, UUID, OffsetDateTime>> sessions = dsl.select(
                        SERVED_ITEM.SESSION_ID, SERVED_ITEM.CONFIG_ID, started)
                .from(SERVED_ITEM)
                .where(SERVED_ITEM.USER_ID.eq(userId))
                .groupBy(SERVED_ITEM.SESSION_ID, SERVED_ITEM.CONFIG_ID)
                .having(
                        after == null
                                ? DSL.noCondition()
                                : DSL.row(DSL.min(SERVED_ITEM.SERVED_AT), SERVED_ITEM.SESSION_ID)
                                        .lt(after.at().atOffset(ZoneOffset.UTC), after.id()))
                .orderBy(DSL.field(DSL.name("started")).desc(), SERVED_ITEM.SESSION_ID.desc())
                .limit(size + 1)
                .fetch();
        boolean more = sessions.size() > size;
        List<Record3<UUID, UUID, OffsetDateTime>> page = more ? sessions.subList(0, size) : sessions;
        if (page.isEmpty()) {
            return Page.of(List.of());
        }

        List<UUID> sessionIds = page.stream().map(Record3::value1).toList();
        OffsetDateTime earliest = page.getLast().value3();
        // A session's pages are served within its 30 minutes; the bound lets the partitions be pruned.
        OffsetDateTime latest = page.getFirst().value3().plus(Duration.ofHours(1));
        Map<UUID, List<OutcomeRow>> rows = new LinkedHashMap<>();
        dsl.select(
                        SERVED_OUTCOME.SESSION_ID,
                        SERVED_OUTCOME.POSITION,
                        SERVED_OUTCOME.POST_ID,
                        SERVED_OUTCOME.SCORE,
                        SERVED_OUTCOME.EXPLORATION,
                        SERVED_OUTCOME.POSITIVE,
                        SERVED_OUTCOME.NEGATIVE,
                        SERVED_OUTCOME.MAX_WATCH_FRACTION)
                .from(SERVED_OUTCOME)
                .where(SERVED_OUTCOME.USER_ID.eq(userId))
                .and(SERVED_OUTCOME.SESSION_ID.in(sessionIds))
                .and(SERVED_OUTCOME.SERVED_AT.between(earliest, latest))
                .orderBy(SERVED_OUTCOME.SESSION_ID, SERVED_OUTCOME.POSITION)
                .fetch()
                .forEach(r -> rows.computeIfAbsent(r.value1(), k -> new ArrayList<>())
                        .add(new OutcomeRow(
                                r.value2(),
                                r.value3(),
                                r.value4(),
                                Boolean.TRUE.equals(r.value5()),
                                Boolean.TRUE.equals(r.value6()),
                                Boolean.TRUE.equals(r.value7()),
                                r.value8() == null ? null : r.value8().doubleValue())));

        Map<UUID, Integer> versions = dsl.select(RANKING_CONFIG.ID, RANKING_CONFIG.VERSION)
                .from(RANKING_CONFIG)
                .where(RANKING_CONFIG.ID.in(page.stream().map(Record3::value2).collect(Collectors.toSet())))
                .fetchMap(RANKING_CONFIG.ID, RANKING_CONFIG.VERSION);
        List<UUID> postIds = rows.values().stream()
                .flatMap(List::stream)
                .map(OutcomeRow::postId)
                .distinct()
                .toList();
        Map<UUID, Post> visible = posts.byIds(userId, postIds).stream()
                .collect(Collectors.toMap(Post::id, Function.identity(), (x, y) -> x));

        List<ServedSession> items = page.stream()
                .map(s -> {
                    List<OutcomeRow> served = rows.getOrDefault(s.value1(), List.of());
                    List<ServedEntry> entries = served.stream()
                            .map(o -> new ServedEntry(
                                    o.position() + 1,
                                    o.postId(),
                                    visible.get(o.postId()),
                                    o.score(),
                                    o.exploration(),
                                    o.positive(),
                                    o.negative(),
                                    o.maxWatchFraction()))
                            .toList();
                    return new ServedSession(
                            s.value1(),
                            s.value3().toInstant(),
                            s.value2(),
                            versions.get(s.value2()),
                            entries.size(),
                            (int) entries.stream().filter(ServedEntry::positive).count(),
                            (int) entries.stream().filter(ServedEntry::negative).count(),
                            entries);
                })
                .toList();
        Record3<UUID, UUID, OffsetDateTime> last = page.getLast();
        String next = more ? new Cursor(last.value3().toInstant(), last.value1()).encode() : null;
        return new Page<>(items, next);
    }

    private record OutcomeRow(
            int position,
            UUID postId,
            double score,
            boolean exploration,
            boolean positive,
            boolean negative,
            @Nullable Double maxWatchFraction) {}

    private UserSummary user(UUID userId) {
        UserSummary user = users.summaries(Set.of(userId)).get(userId);
        if (user == null) {
            throw ApiException.notFound("USER_NOT_FOUND", "No user " + userId);
        }
        return user;
    }

    private static List<Map.Entry<UUID, Double>> strongest(Map<UUID, Double> scores) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .toList();
    }
}
