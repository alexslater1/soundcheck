package app.soundcheck.feed.internal;

import static app.soundcheck.shared.jooq.feed.Tables.ARTIST_GENRE;
import static app.soundcheck.shared.jooq.feed.Tables.GIG_PLACE;
import static app.soundcheck.shared.jooq.feed.Tables.POST_CANDIDATE;
import static app.soundcheck.shared.jooq.feed.Tables.POST_ENGAGER;
import static app.soundcheck.shared.jooq.feed.Tables.POST_STAT;
import static app.soundcheck.shared.jooq.feed.Tables.POST_STAT_HOURLY;
import static app.soundcheck.shared.jooq.feed.Tables.POST_TAG;

import app.soundcheck.catalog.GeoPoint;
import app.soundcheck.feed.internal.ranking.CandidateSource;
import app.soundcheck.feed.internal.ranking.RankingConfig;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.CommonTableExpression;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.Record5;
import org.jooq.Select;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/**
 * The SQL of the ranking pipeline, over the views of V017: candidate generation as
 * one statement, and one batched query per kind of fact the features need. Nothing here runs once per
 * post.
 */
@Repository
public class CandidateRepository {

    /** A post that may be ranked, and every generator that produced it. */
    public record Candidate(
            UUID postId, UUID authorId, @Nullable UUID gigId, Instant publishedAt, Set<CandidateSource> sources) {}

    /** A post's counters, as far as ranking uses them. */
    public record Stats(long impressions, long likes, long comments, long shares, long completes) {
        static final Stats NONE = new Stats(0, 0, 0, 0, 0);
    }

    // The candidate windows.
    static final Duration FOLLOWING_WINDOW = Duration.ofDays(14);
    static final Duration ARTIST_WINDOW = Duration.ofDays(30);
    static final Duration TASTE_WINDOW = Duration.ofDays(7);
    static final Duration LOCAL_WINDOW = Duration.ofDays(7);
    static final Duration TRENDING_WINDOW = Duration.ofHours(48);
    static final Duration FRESH_WINDOW = Duration.ofHours(72);
    static final long FRESH_MAX_IMPRESSIONS = 50;

    private static final Field<String> SOURCE = DSL.field(DSL.name("source"), String.class);

    private final DSLContext dsl;

    CandidateRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ── candidates ───────────────────────────────────────────────────────────────────────────────

    /**
     * Runs every candidate generator in one statement: each is a SELECT with its own rule, order and
     * cap, joined with UNION ALL. A post found by several generators comes back once per generator and
     * keeps all its sources (they explain it later).
     *
     * <p>Every branch applies the same filters: a servable post (the view), not the viewer's own, no
     * block with its author, not hidden by the viewer.
     */
    public List<Candidate> candidates(UserContext viewer, RankingConfig.Params params, Instant now) {
        // Engagement per post over the last week, and over the last 48 hours.
        CommonTableExpression<Record2<UUID, Long>> recent = engagementSince("recent", now.minus(TASTE_WINDOW));
        CommonTableExpression<Record2<UUID, Long>> trending = engagementSince("trending", now.minus(TRENDING_WINDOW));
        Condition allowed = POST_CANDIDATE
                .AUTHOR_ID
                .ne(viewer.userId())
                .and(POST_CANDIDATE.AUTHOR_ID.ne(DSL.all(uuids(viewer.blocked()))))
                .and(POST_CANDIDATE.POST_ID.ne(DSL.all(uuids(viewer.hidden()))));

        List<Select<Record5<UUID, UUID, UUID, OffsetDateTime, String>>> generators = new ArrayList<>();

        // FOLLOWING: published by followed users, last 14 days.
        if (params.cap(CandidateSource.FOLLOWING) > 0 && !viewer.followedUsers().isEmpty()) {
            generators.add(candidate(CandidateSource.FOLLOWING)
                    .from(POST_CANDIDATE)
                    .where(allowed)
                    .and(POST_CANDIDATE.AUTHOR_ID.eq(DSL.any(uuids(viewer.followedUsers()))))
                    .and(POST_CANDIDATE.PUBLISHED_AT.ge(at(now.minus(FOLLOWING_WINDOW))))
                    .orderBy(POST_CANDIDATE.PUBLISHED_AT.desc())
                    .limit(params.cap(CandidateSource.FOLLOWING)));
        }
        // ARTIST: tagged with a followed artist, last 30 days.
        if (params.cap(CandidateSource.ARTIST) > 0 && !viewer.followedArtists().isEmpty()) {
            generators.add(candidate(CandidateSource.ARTIST)
                    .from(POST_CANDIDATE)
                    .where(allowed)
                    .and(DSL.exists(DSL.selectOne()
                            .from(POST_TAG)
                            .where(POST_TAG.POST_ID.eq(POST_CANDIDATE.POST_ID))
                            .and(POST_TAG.ARTIST_ID.eq(DSL.any(uuids(viewer.followedArtists()))))))
                    .and(POST_CANDIDATE.PUBLISHED_AT.ge(at(now.minus(ARTIST_WINDOW))))
                    .orderBy(POST_CANDIDATE.PUBLISHED_AT.desc())
                    .limit(params.cap(CandidateSource.ARTIST)));
        }
        // GIG: from gigs the viewer is interested in, going to or went to, any age.
        if (params.cap(CandidateSource.GIG) > 0 && !viewer.attendance().isEmpty()) {
            generators.add(candidate(CandidateSource.GIG)
                    .from(POST_CANDIDATE)
                    .where(allowed)
                    .and(POST_CANDIDATE.GIG_ID.eq(
                            DSL.any(uuids(viewer.attendance().keySet()))))
                    .orderBy(POST_CANDIDATE.PUBLISHED_AT.desc())
                    .limit(params.cap(CandidateSource.GIG)));
        }
        // TASTE: a tagged artist has one of the viewer's top genres, last 7 days, most engaged first.
        if (params.cap(CandidateSource.TASTE) > 0 && !viewer.topGenres().isEmpty()) {
            generators.add(candidate(CandidateSource.TASTE)
                    .from(POST_CANDIDATE)
                    .leftJoin(recent)
                    .on(recent.field(POST_STAT_HOURLY.POST_ID).eq(POST_CANDIDATE.POST_ID))
                    .where(allowed)
                    .and(DSL.exists(DSL.selectOne()
                            .from(POST_TAG)
                            .join(ARTIST_GENRE)
                            .on(ARTIST_GENRE.ARTIST_ID.eq(POST_TAG.ARTIST_ID))
                            .where(POST_TAG.POST_ID.eq(POST_CANDIDATE.POST_ID))
                            .and(ARTIST_GENRE.GENRE_ID.eq(
                                    DSL.any(viewer.topGenres().toArray(Short[]::new))))))
                    .and(POST_CANDIDATE.PUBLISHED_AT.ge(at(now.minus(TASTE_WINDOW))))
                    .orderBy(engagement(recent).desc(), POST_CANDIDATE.PUBLISHED_AT.desc())
                    .limit(params.cap(CandidateSource.TASTE)));
        }
        // LOCAL: the gig is near the viewer's home, last 7 days, most engaged first.
        if (params.cap(CandidateSource.LOCAL) > 0 && viewer.home() != null) {
            generators.add(candidate(CandidateSource.LOCAL)
                    .from(POST_CANDIDATE)
                    .join(GIG_PLACE)
                    .on(GIG_PLACE.GIG_ID.eq(POST_CANDIDATE.GIG_ID))
                    .leftJoin(recent)
                    .on(recent.field(POST_STAT_HOURLY.POST_ID).eq(POST_CANDIDATE.POST_ID))
                    .where(allowed)
                    .and(DSL.condition(
                            "ST_DWithin({0}, {1}, {2})",
                            GIG_PLACE.LOCATION, point(viewer.home()), DSL.val(params.localRadiusKm() * 1000.0)))
                    .and(POST_CANDIDATE.PUBLISHED_AT.ge(at(now.minus(LOCAL_WINDOW))))
                    .orderBy(engagement(recent).desc(), POST_CANDIDATE.PUBLISHED_AT.desc())
                    .limit(params.cap(CandidateSource.LOCAL)));
        }
        // C6 TRENDING: the most engagement in the last 48 hours.
        if (params.cap(CandidateSource.TRENDING) > 0) {
            generators.add(candidate(CandidateSource.TRENDING)
                    .from(POST_CANDIDATE)
                    .join(trending)
                    .on(trending.field(POST_STAT_HOURLY.POST_ID).eq(POST_CANDIDATE.POST_ID))
                    .where(allowed)
                    .and(engagement(trending).gt(0L))
                    .orderBy(engagement(trending).desc(), POST_CANDIDATE.PUBLISHED_AT.desc())
                    .limit(params.cap(CandidateSource.TRENDING)));
        }
        // FRESH: published in the last 72 hours and hardly shown yet (the exploration pool).
        if (params.cap(CandidateSource.FRESH) > 0) {
            generators.add(candidate(CandidateSource.FRESH)
                    .from(POST_CANDIDATE)
                    .leftJoin(POST_STAT)
                    .on(POST_STAT.POST_ID.eq(POST_CANDIDATE.POST_ID))
                    .where(allowed)
                    .and(POST_CANDIDATE.PUBLISHED_AT.ge(at(now.minus(FRESH_WINDOW))))
                    .and(DSL.coalesce(POST_STAT.IMPRESSIONS, 0L).lt(FRESH_MAX_IMPRESSIONS))
                    .orderBy(POST_CANDIDATE.PUBLISHED_AT.desc())
                    .limit(params.cap(CandidateSource.FRESH)));
        }
        if (generators.isEmpty()) {
            return List.of();
        }
        Select<Record5<UUID, UUID, UUID, OffsetDateTime, String>> union = generators.getFirst();
        for (int i = 1; i < generators.size(); i++) {
            union = union.unionAll(generators.get(i));
        }

        Map<UUID, Candidate> byPost = new LinkedHashMap<>();
        dsl.with(recent).with(trending).selectFrom(union.asTable("candidates")).forEach(r -> {
            UUID postId = r.get(POST_CANDIDATE.POST_ID);
            Candidate candidate = byPost.computeIfAbsent(
                    postId,
                    id -> new Candidate(
                            id,
                            r.get(POST_CANDIDATE.AUTHOR_ID),
                            r.get(POST_CANDIDATE.GIG_ID),
                            r.get(POST_CANDIDATE.PUBLISHED_AT).toInstant(),
                            EnumSet.noneOf(CandidateSource.class)));
            candidate.sources().add(CandidateSource.valueOf(r.get(SOURCE)));
        });
        return List.copyOf(byPost.values());
    }

    private org.jooq.SelectSelectStep<Record5<UUID, UUID, UUID, OffsetDateTime, String>> candidate(
            CandidateSource source) {
        return DSL.select(
                POST_CANDIDATE.POST_ID,
                POST_CANDIDATE.AUTHOR_ID,
                POST_CANDIDATE.GIG_ID,
                POST_CANDIDATE.PUBLISHED_AT,
                DSL.inline(source.name()).as(SOURCE));
    }

    /** post id → engagements since the given moment (expected index: the post_stats_hourly primary key, hour first). */
    private CommonTableExpression<Record2<UUID, Long>> engagementSince(String name, Instant since) {
        return DSL.name(name)
                .fields("post_id", "engagements")
                .as(DSL.select(
                                POST_STAT_HOURLY.POST_ID,
                                DSL.sum(POST_STAT_HOURLY.ENGAGEMENTS).cast(Long.class))
                        .from(POST_STAT_HOURLY)
                        .where(POST_STAT_HOURLY.HOUR.ge(at(since)))
                        .groupBy(POST_STAT_HOURLY.POST_ID));
    }

    private static Field<Long> engagement(CommonTableExpression<Record2<UUID, Long>> window) {
        return DSL.coalesce(window.field("engagements", Long.class), 0L);
    }

    // ── facts for the features, one query each for all candidates ────────────────────────────────

    /** post id → tagged artists. */
    public Map<UUID, List<UUID>> tags(Collection<UUID> postIds) {
        Map<UUID, List<UUID>> byPost = new HashMap<>();
        dsl.select(POST_TAG.POST_ID, POST_TAG.ARTIST_ID)
                .from(POST_TAG)
                .where(POST_TAG.POST_ID.eq(DSL.any(uuids(postIds))))
                .forEach(r -> byPost.computeIfAbsent(r.value1(), id -> new ArrayList<>())
                        .add(r.value2()));
        return byPost;
    }

    /** artist id → genre id → weight. */
    public Map<UUID, Map<Short, Double>> genres(Collection<UUID> artistIds) {
        Map<UUID, Map<Short, Double>> byArtist = new HashMap<>();
        dsl.select(ARTIST_GENRE.ARTIST_ID, ARTIST_GENRE.GENRE_ID, ARTIST_GENRE.WEIGHT)
                .from(ARTIST_GENRE)
                .where(ARTIST_GENRE.ARTIST_ID.eq(DSL.any(uuids(artistIds))))
                .forEach(r -> byArtist.computeIfAbsent(r.value1(), id -> new HashMap<>())
                        .put(r.value2(), r.value3().doubleValue()));
        return byArtist;
    }

    /** post id → counters; posts never shown have none. */
    public Map<UUID, Stats> stats(Collection<UUID> postIds) {
        Map<UUID, Stats> byPost = new HashMap<>();
        dsl.selectFrom(POST_STAT)
                .where(POST_STAT.POST_ID.eq(DSL.any(uuids(postIds))))
                .forEach(r -> byPost.put(
                        r.getPostId(),
                        new Stats(r.getImpressions(), r.getLikes(), r.getComments(), r.getShares(), r.getCompletes())));
        return byPost;
    }

    /** gig id → kilometres from {@code home} to the gig's venue; gigs without a location are absent. */
    public Map<UUID, Double> distancesKm(Collection<UUID> gigIds, @Nullable GeoPoint home) {
        Map<UUID, Double> byGig = new HashMap<>();
        if (home == null || gigIds.isEmpty()) {
            return byGig;
        }
        Field<Double> km = DSL.field("ST_Distance({0}, {1}) / 1000.0", Double.class, GIG_PLACE.LOCATION, point(home));
        dsl.select(GIG_PLACE.GIG_ID, km)
                .from(GIG_PLACE)
                .where(GIG_PLACE.GIG_ID.eq(DSL.any(uuids(gigIds))))
                .and(DSL.condition("{0} is not null", GIG_PLACE.LOCATION))
                .forEach(r -> byGig.put(r.value1(), r.value2()));
        return byGig;
    }

    /** post id → how many of the given users liked or commented on it. */
    public Map<UUID, Integer> engagedAmong(Collection<UUID> postIds, Collection<UUID> userIds) {
        Map<UUID, Integer> byPost = new HashMap<>();
        if (userIds.isEmpty()) {
            return byPost;
        }
        dsl.select(POST_ENGAGER.POST_ID, DSL.countDistinct(POST_ENGAGER.USER_ID))
                .from(POST_ENGAGER)
                .where(POST_ENGAGER.POST_ID.eq(DSL.any(uuids(postIds))))
                .and(POST_ENGAGER.USER_ID.eq(DSL.any(uuids(userIds))))
                .groupBy(POST_ENGAGER.POST_ID)
                .forEach(r -> byPost.put(r.value1(), r.value2()));
        return byPost;
    }

    private static UUID[] uuids(Collection<UUID> ids) {
        return ids.toArray(UUID[]::new);
    }

    private static Field<Object> point(GeoPoint p) {
        return DSL.field(
                "ST_SetSRID(ST_MakePoint({0}, {1}), 4326)::geography",
                Object.class, DSL.val(p.lng()), DSL.val(p.lat()));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
