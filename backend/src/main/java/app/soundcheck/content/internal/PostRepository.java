package app.soundcheck.content.internal;

import static app.soundcheck.shared.jooq.content.Tables.POST;
import static app.soundcheck.shared.jooq.content.Tables.POST_ARTIST;
import static app.soundcheck.shared.jooq.content.Tables.POST_HIDE;
import static app.soundcheck.shared.jooq.content.Tables.POST_LIKE;

import app.soundcheck.shared.web.Cursor;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** jOOQ access to {@code content.post} and {@code content.post_artist}. */
@Repository
public class PostRepository {

    private final DSLContext dsl;

    PostRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(PostRow post, List<UUID> artistIds) {
        dsl.insertInto(POST)
                .set(POST.ID, post.id())
                .set(POST.AUTHOR_ID, post.authorId())
                .set(POST.MEDIA_ID, post.mediaId())
                .set(POST.GIG_ID, post.gigId())
                .set(POST.CAPTION, post.caption())
                .set(POST.STATUS, post.status())
                .set(POST.CREATED_AT, at(post.createdAt()))
                .set(POST.UPDATED_AT, at(post.createdAt()))
                .set(POST.PUBLISHED_AT, post.publishedAt() == null ? null : at(post.publishedAt()))
                .execute();
        for (UUID artistId : artistIds) {
            dsl.insertInto(POST_ARTIST)
                    .set(POST_ARTIST.POST_ID, post.id())
                    .set(POST_ARTIST.ARTIST_ID, artistId)
                    .execute();
        }
    }

    public Optional<PostRow> find(UUID id) {
        return dsl.selectFrom(POST).where(POST.ID.eq(id)).fetchOptional(PostRepository::toRow);
    }

    /** Expected index: the unique constraint on media_id. */
    public Optional<PostRow> findByMedia(UUID mediaId) {
        return dsl.selectFrom(POST).where(POST.MEDIA_ID.eq(mediaId)).fetchOptional(PostRepository::toRow);
    }

    /** Posts by id, in no particular order. */
    public List<PostRow> findAll(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(POST).where(POST.ID.in(ids)).fetch(PostRepository::toRow);
    }

    /**
     * PENDING_MEDIA → PUBLISHED, only if it is still pending (a second {@code MediaReady}, or the
     * reconciler racing the listener, changes nothing).
     *
     * @return true when this call published it
     */
    public boolean publish(UUID postId, Instant now) {
        return dsl.update(POST)
                        .set(POST.STATUS, "PUBLISHED")
                        .set(POST.PUBLISHED_AT, at(now))
                        .set(POST.UPDATED_AT, at(now))
                        .where(POST.ID.eq(postId))
                        .and(POST.STATUS.eq("PENDING_MEDIA"))
                        .execute()
                == 1;
    }

    /** PENDING_MEDIA → FAILED, only if it is still pending. */
    public boolean fail(UUID postId, Instant now) {
        return dsl.update(POST)
                        .set(POST.STATUS, "FAILED")
                        .set(POST.UPDATED_AT, at(now))
                        .where(POST.ID.eq(postId))
                        .and(POST.STATUS.eq("PENDING_MEDIA"))
                        .execute()
                == 1;
    }

    /** Soft delete; false when it was already deleted. */
    public boolean markDeleted(UUID postId, Instant now) {
        return dsl.update(POST)
                        .set(POST.STATUS, "DELETED")
                        .set(POST.UPDATED_AT, at(now))
                        .where(POST.ID.eq(postId))
                        .and(POST.STATUS.ne("DELETED"))
                        .execute()
                == 1;
    }

    /** Which published posts a list shows; exactly one of the ids is set. */
    public record Scope(
            @Nullable UUID authorId, @Nullable UUID gigId, @Nullable Collection<UUID> gigIds, @Nullable UUID artistId) {

        public static Scope author(UUID id) {
            return new Scope(id, null, null, null);
        }

        public static Scope gig(UUID id) {
            return new Scope(null, id, null, null);
        }

        public static Scope gigs(Collection<UUID> ids) {
            return new Scope(null, null, ids, null);
        }

        public static Scope artist(UUID id) {
            return new Scope(null, null, null, id);
        }
    }

    /**
     * One page of PUBLISHED posts, newest first, in {@code (published_at, id)} order; {@code limit + 1}
     * rows so the caller can tell whether a next page exists. Expected indexes: {@code post_author_pub}
     * (author), {@code post_gig_pub} (gig and venue), {@code post_artist_artist} then the primary key
     * (artist).
     */
    public List<PostRow> published(Scope scope, @Nullable Cursor cursor, int limit) {
        List<Condition> where = new ArrayList<>();
        where.add(POST.STATUS.eq("PUBLISHED"));
        if (scope.authorId() != null) {
            where.add(POST.AUTHOR_ID.eq(scope.authorId()));
        }
        if (scope.gigId() != null) {
            where.add(POST.GIG_ID.eq(scope.gigId()));
        }
        if (scope.gigIds() != null) {
            if (scope.gigIds().isEmpty()) {
                return List.of();
            }
            where.add(POST.GIG_ID.in(scope.gigIds()));
        }
        if (scope.artistId() != null) {
            where.add(DSL.exists(DSL.selectOne()
                    .from(POST_ARTIST)
                    .where(POST_ARTIST.POST_ID.eq(POST.ID))
                    .and(POST_ARTIST.ARTIST_ID.eq(scope.artistId()))));
        }
        if (cursor != null) {
            where.add(DSL.row(POST.PUBLISHED_AT, POST.ID).lt(DSL.row(at(cursor.at()), cursor.id())));
        }
        return dsl.selectFrom(POST)
                .where(where)
                .orderBy(POST.PUBLISHED_AT.desc(), POST.ID.desc())
                .limit(limit + 1)
                .fetch(PostRepository::toRow);
    }

    /**
     * Published posts by any of the authors that the viewer has not hidden, newest first;
     * {@code limit + 1} rows. Expected indexes: {@code post_author_pub} per author, the post_hide primary
     * key for the anti-join.
     */
    public List<PostRow> byAuthors(Collection<UUID> authorIds, UUID viewerId, @Nullable Cursor cursor, int limit) {
        if (authorIds.isEmpty()) {
            return List.of();
        }
        Condition where = POST.STATUS
                .eq("PUBLISHED")
                .and(POST.AUTHOR_ID.in(authorIds))
                .and(DSL.notExists(DSL.selectOne()
                        .from(POST_HIDE)
                        .where(POST_HIDE.POST_ID.eq(POST.ID))
                        .and(POST_HIDE.USER_ID.eq(viewerId))));
        if (cursor != null) {
            where = where.and(DSL.row(POST.PUBLISHED_AT, POST.ID).lt(DSL.row(at(cursor.at()), cursor.id())));
        }
        return dsl.selectFrom(POST)
                .where(where)
                .orderBy(POST.PUBLISHED_AT.desc(), POST.ID.desc())
                .limit(limit + 1)
                .fetch(PostRepository::toRow);
    }

    /**
     * The author's own posts in every status except DELETED, newest first by creation. Expected index:
     * {@code post_author_all (author_id, created_at desc)}.
     */
    public List<PostRow> ownPosts(UUID authorId, @Nullable Cursor cursor, int limit) {
        Condition where = POST.AUTHOR_ID.eq(authorId).and(POST.STATUS.ne("DELETED"));
        if (cursor != null) {
            where = where.and(DSL.row(POST.CREATED_AT, POST.ID).lt(DSL.row(at(cursor.at()), cursor.id())));
        }
        return dsl.selectFrom(POST)
                .where(where)
                .orderBy(POST.CREATED_AT.desc(), POST.ID.desc())
                .limit(limit + 1)
                .fetch(PostRepository::toRow);
    }

    /** Posts still waiting for their clip that were created before {@code before} (reconciliation). */
    public List<PostRow> pendingCreatedBefore(Instant before, int limit) {
        return dsl.selectFrom(POST)
                .where(POST.STATUS.eq("PENDING_MEDIA"))
                .and(POST.CREATED_AT.lt(at(before)))
                .orderBy(POST.CREATED_AT)
                .limit(limit)
                .fetch(PostRepository::toRow);
    }

    /** How many published posts an author has. Expected index: post_author_pub. */
    /** Expected index: post_gig_pub (gig_id, published_at desc) where status = 'PUBLISHED'. */
    public long countPublishedAt(UUID gigId) {
        return dsl.fetchCount(POST, POST.GIG_ID.eq(gigId).and(POST.STATUS.eq("PUBLISHED")));
    }

    public long countPublished(UUID authorId) {
        return dsl.fetchCount(POST, POST.AUTHOR_ID.eq(authorId).and(POST.STATUS.eq("PUBLISHED")));
    }

    /** The artists tagged in each post. Expected index: the post_artist primary key. */
    public Map<UUID, List<UUID>> artistIds(Collection<UUID> postIds) {
        Map<UUID, List<UUID>> byPost = new LinkedHashMap<>();
        if (postIds.isEmpty()) {
            return byPost;
        }
        dsl.select(POST_ARTIST.POST_ID, POST_ARTIST.ARTIST_ID)
                .from(POST_ARTIST)
                .where(POST_ARTIST.POST_ID.in(postIds))
                .forEach(r -> byPost.computeIfAbsent(r.value1(), id -> new ArrayList<>())
                        .add(r.value2()));
        return byPost;
    }

    /** Which of the posts the viewer has liked. Expected index: the post_like primary key. */
    public Set<UUID> likedBy(UUID viewerId, Collection<UUID> postIds) {
        if (postIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(dsl.select(POST_LIKE.POST_ID)
                .from(POST_LIKE)
                .where(POST_LIKE.USER_ID.eq(viewerId))
                .and(POST_LIKE.POST_ID.in(postIds))
                .fetch(POST_LIKE.POST_ID));
    }

    static PostRow toRow(Record r) {
        return new PostRow(
                r.get(POST.ID),
                r.get(POST.AUTHOR_ID),
                r.get(POST.MEDIA_ID),
                r.get(POST.GIG_ID),
                r.get(POST.CAPTION),
                r.get(POST.STATUS),
                r.get(POST.LIKE_COUNT),
                r.get(POST.COMMENT_COUNT),
                r.get(POST.CREATED_AT).toInstant(),
                r.get(POST.PUBLISHED_AT) == null
                        ? null
                        : r.get(POST.PUBLISHED_AT).toInstant());
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
