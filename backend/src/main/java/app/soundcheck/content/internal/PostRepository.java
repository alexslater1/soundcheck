package app.soundcheck.content.internal;

import static app.soundcheck.shared.jooq.content.Tables.POST;
import static app.soundcheck.shared.jooq.content.Tables.POST_ARTIST;
import static app.soundcheck.shared.jooq.content.Tables.POST_CLIP;
import static app.soundcheck.shared.jooq.content.Tables.POST_HIDE;
import static app.soundcheck.shared.jooq.content.Tables.POST_LIKE;
import static app.soundcheck.shared.jooq.content.Tables.POST_PERSON;

import app.soundcheck.shared.web.Cursor;
import java.time.Instant;
import java.time.LocalDate;
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
import org.jooq.Record1;
import org.jooq.Select;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/**
 * jOOQ access to {@code content.post} and {@code content.post_artist}. A post's clips are in
 * {@link ClipRepository}, its tagged people in {@link TagRepository}.
 */
@Repository
public class PostRepository {

    private final DSLContext dsl;

    PostRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Inserts the post unless the author already has an open post from the same gig and day (the partial
     * unique index post_author_gig_day decides, so two requests at once cannot both make one).
     *
     * @return false when there was one, and nothing was inserted
     */
    public boolean insertUnlessOpen(PostRow post, List<UUID> artistIds) {
        int inserted = dsl.insertInto(POST)
                .set(POST.ID, post.id())
                .set(POST.AUTHOR_ID, post.authorId())
                .set(POST.GIG_ID, post.gigId())
                .set(POST.DAY, post.day())
                .set(POST.CAPTION, post.caption())
                .set(POST.STATUS, post.status())
                .set(POST.CREATED_AT, at(post.createdAt()))
                .set(POST.UPDATED_AT, at(post.createdAt()))
                .set(POST.PUBLISHED_AT, post.publishedAt() == null ? null : at(post.publishedAt()))
                .onConflict(POST.AUTHOR_ID, POST.GIG_ID, POST.DAY)
                .where(POST.GIG_ID.isNotNull().and(POST.STATUS.in("PUBLISHED", "PENDING_MEDIA")))
                .doNothing()
                .execute();
        if (inserted == 0) {
            return false;
        }
        addArtists(post.id(), artistIds);
        return true;
    }

    /** Tags more acts; ones already tagged stay as they are. */
    public void addArtists(UUID postId, Collection<UUID> artistIds) {
        for (UUID artistId : artistIds) {
            dsl.insertInto(POST_ARTIST)
                    .set(POST_ARTIST.POST_ID, postId)
                    .set(POST_ARTIST.ARTIST_ID, artistId)
                    .onConflictDoNothing()
                    .execute();
        }
    }

    /** The post's acts become exactly these. */
    public void replaceArtists(UUID postId, Collection<UUID> artistIds) {
        dsl.deleteFrom(POST_ARTIST)
                .where(POST_ARTIST.POST_ID.eq(postId))
                .and(POST_ARTIST.ARTIST_ID.notIn(artistIds))
                .execute();
        addArtists(postId, artistIds);
    }

    public void setCaption(UUID postId, @Nullable String caption, Instant now) {
        dsl.update(POST)
                .set(POST.CAPTION, caption)
                .set(POST.UPDATED_AT, at(now))
                .where(POST.ID.eq(postId))
                .execute();
    }

    public void setGig(UUID postId, @Nullable UUID gigId, @Nullable LocalDate day, Instant now) {
        dsl.update(POST)
                .set(POST.GIG_ID, gigId)
                .set(POST.DAY, day)
                .set(POST.UPDATED_AT, at(now))
                .where(POST.ID.eq(postId))
                .execute();
    }

    /** Locks the post's row until the transaction ends: its clips change one writer at a time. */
    public Optional<PostRow> lock(UUID id) {
        return dsl.selectFrom(POST).where(POST.ID.eq(id)).forUpdate().fetchOptional(PostRepository::toRow);
    }

    /**
     * The author's open (live or pending) post from a gig and day, locked: new clips from them join it.
     * Expected index: post_author_gig_day (unique, partial).
     */
    public Optional<PostRow> lockOpen(UUID authorId, UUID gigId, LocalDate day) {
        return dsl.selectFrom(POST)
                .where(POST.AUTHOR_ID.eq(authorId))
                .and(POST.GIG_ID.eq(gigId))
                .and(POST.DAY.eq(day))
                .and(POST.STATUS.in("PUBLISHED", "PENDING_MEDIA"))
                .forUpdate()
                .fetchOptional(PostRepository::toRow);
    }

    public Optional<PostRow> find(UUID id) {
        return dsl.selectFrom(POST).where(POST.ID.eq(id)).fetchOptional(PostRepository::toRow);
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

    /**
     * PUBLISHED → HIDDEN by a moderator, only if it is still published.
     *
     * @return true when this call hid it
     */
    public boolean hide(UUID postId, Instant now) {
        return dsl.update(POST)
                        .set(POST.STATUS, "HIDDEN")
                        .set(POST.UPDATED_AT, at(now))
                        .where(POST.ID.eq(postId))
                        .and(POST.STATUS.eq("PUBLISHED"))
                        .execute()
                == 1;
    }

    /**
     * HIDDEN → PUBLISHED (a moderator's restore), keeping its first {@code published_at} so it goes back
     * to its place in every list. The partial unique index post_author_gig_day refuses it if the author
     * has posted again from the same gig and day meanwhile; the caller checks first and translates a
     * refusal that slipped past the check.
     *
     * @return true when this call restored it
     */
    public boolean unhide(UUID postId, Instant now) {
        return dsl.update(POST)
                        .set(POST.STATUS, "PUBLISHED")
                        .set(POST.UPDATED_AT, at(now))
                        .where(POST.ID.eq(postId))
                        .and(POST.STATUS.eq("HIDDEN"))
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

    /**
     * Whether the user still has a clip in a live post of the gig (PUBLISHED, or HIDDEN by moderation: it
     * still exists), the reason a WENT made by posts stays (per clip owner, so a
     * collaborator counts). Expected index: post_clip_owner (owner_id, post_id), then the post's primary
     * key; one user has at most a few hundred clips.
     */
    public boolean hasLivePost(UUID userId, UUID gigId) {
        return dsl.fetchExists(DSL.selectOne()
                .from(POST_CLIP)
                .join(POST)
                .on(POST.ID.eq(POST_CLIP.POST_ID))
                .where(POST_CLIP.OWNER_ID.eq(userId))
                .and(POST_CLIP.MEDIA_ID.isNotNull())
                .and(POST.GIG_ID.eq(gigId))
                .and(POST.STATUS.in("PUBLISHED", "HIDDEN")));
    }

    /**
     * Which of the gigs the user has posted from: an open (live or pending) post of theirs, or a clip of
     * theirs in someone's open post (the Pending list leaves these out). Expected indexes:
     * post_author_all (author_id, …), post_clip_owner (owner_id, post_id).
     */
    public Set<UUID> postedGigs(UUID userId, Collection<UUID> gigIds) {
        if (gigIds.isEmpty()) {
            return Set.of();
        }
        Condition open = POST.STATUS.in("PUBLISHED", "PENDING_MEDIA", "HIDDEN").and(POST.GIG_ID.in(gigIds));
        Set<UUID> result = new HashSet<>(dsl.select(POST.GIG_ID)
                .from(POST)
                .where(POST.AUTHOR_ID.eq(userId))
                .and(open)
                .fetch(POST.GIG_ID));
        result.addAll(dsl.select(POST.GIG_ID)
                .from(POST_CLIP)
                .join(POST)
                .on(POST.ID.eq(POST_CLIP.POST_ID))
                .where(POST_CLIP.OWNER_ID.eq(userId))
                .and(open)
                .fetch(POST.GIG_ID));
        return result;
    }

    /** Which published posts a list shows: one of the ids, or a gig and an act together. */
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

        /** A gig's posts with a clip of one act. */
        public static Scope gigAndArtist(UUID gigId, UUID artistId) {
            return new Scope(null, gigId, null, artistId);
        }
    }

    /**
     * One page of PUBLISHED posts, newest first, in {@code (published_at, id)} order; {@code limit + 1}
     * rows so the caller can tell whether a next page exists. Expected indexes: {@code post_author_pub}
     * (author), {@code post_gig_pub} (gig and venue; a gig and an act: the gig's posts, then
     * {@code post_clip_post} per post), {@code post_clip_artist} then the primary key (artist).
     */
    public List<PostRow> published(Scope scope, @Nullable Cursor cursor, int limit) {
        List<Condition> where = new ArrayList<>();
        where.add(POST.STATUS.eq("PUBLISHED"));
        if (scope.authorId() != null) {
            where.add(onProfileOf(List.of(scope.authorId())));
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
            // A post with a clip of the act on stage, not one only tagged with it: a KEO
            // clip from a KEO + Tooth night is on KEO's page, not Tooth's.
            where.add(DSL.exists(DSL.selectOne()
                    .from(POST_CLIP)
                    .where(POST_CLIP.POST_ID.eq(POST.ID))
                    .and(POST_CLIP.ARTIST_ID.eq(scope.artistId()))
                    .and(POST_CLIP.MEDIA_ID.isNotNull())));
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
     * Published posts on any of the users' profiles (their own, and those they put on their profile)
     * that the viewer has not hidden, newest first; {@code limit + 1} rows. Expected indexes:
     * {@code post_author_pub} per author, {@code post_person_profile} per user, the post_hide primary key
     * for the anti-join.
     */
    public List<PostRow> byAuthors(Collection<UUID> authorIds, UUID viewerId, @Nullable Cursor cursor, int limit) {
        if (authorIds.isEmpty()) {
            return List.of();
        }
        Condition where = POST.STATUS
                .eq("PUBLISHED")
                .and(onProfileOf(authorIds))
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
     * The author's own posts in every status except DELETED, and the published posts they put on their
     * profile, newest first by creation. Expected indexes: {@code post_author_all (author_id,
     * created_at desc)}, {@code post_person_profile}.
     */
    public List<PostRow> ownPosts(UUID authorId, @Nullable Cursor cursor, int limit) {
        Condition where = POST.AUTHOR_ID
                .eq(authorId)
                .and(POST.STATUS.ne("DELETED"))
                .or(POST.STATUS.eq("PUBLISHED").and(POST.ID.in(profilePosts(List.of(authorId)))));
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

    /**
     * The gigs that have a published post. Once per backfill run; expected index: post_gig_pub
     * (partial on PUBLISHED, leading gig_id), read as an index-only scan.
     */
    public Set<UUID> gigsWithPublished() {
        return new HashSet<>(dsl.selectDistinct(POST.GIG_ID)
                .from(POST)
                .where(POST.STATUS.eq("PUBLISHED"))
                .and(POST.GIG_ID.isNotNull())
                .fetch(POST.GIG_ID));
    }

    /** Expected index: post_gig_pub (gig_id, published_at desc) where status = 'PUBLISHED'. */
    public long countPublishedAt(UUID gigId) {
        return dsl.fetchCount(POST, POST.GIG_ID.eq(gigId).and(POST.STATUS.eq("PUBLISHED")));
    }

    /** A gig's published posts, counted per author. Expected index: post_gig_pub (partial, PUBLISHED). */
    public Map<UUID, Long> countPublishedAtByAuthor(UUID gigId) {
        return dsl.select(POST.AUTHOR_ID, DSL.count())
                .from(POST)
                .where(POST.GIG_ID.eq(gigId))
                .and(POST.STATUS.eq("PUBLISHED"))
                .groupBy(POST.AUTHOR_ID)
                .fetchMap(r -> r.value1(), r -> (long) r.value2());
    }

    /** Published posts on the user's profile: theirs and those they put there. */
    public long countPublished(UUID authorId) {
        return dsl.fetchCount(POST, POST.STATUS.eq("PUBLISHED").and(onProfileOf(List.of(authorId))));
    }

    /**
     * Published posts each of the users wrote (the console's user list); users without any are
     * absent. Expected index: post_author_pub (author_id, published_at desc, id) where status = 'PUBLISHED'.
     */
    public Map<UUID, Long> countPublishedByAuthors(Collection<UUID> authorIds) {
        if (authorIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(POST.AUTHOR_ID, DSL.count())
                .from(POST)
                .where(POST.AUTHOR_ID.in(authorIds))
                .and(POST.STATUS.eq("PUBLISHED"))
                .groupBy(POST.AUTHOR_ID)
                .fetchMap(r -> r.value1(), r -> (long) r.value2());
    }

    /** Posts by any of the users, or put on the profile of any of them. */
    private static Condition onProfileOf(Collection<UUID> userIds) {
        return POST.AUTHOR_ID.in(userIds).or(POST.ID.in(profilePosts(userIds)));
    }

    /** Expected index: post_person_profile (user_id, post_id) where status = 'ON_PROFILE'. */
    private static Select<Record1<UUID>> profilePosts(Collection<UUID> userIds) {
        return DSL.select(POST_PERSON.POST_ID)
                .from(POST_PERSON)
                .where(POST_PERSON.USER_ID.in(userIds))
                .and(POST_PERSON.STATUS.eq("ON_PROFILE"));
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
                r.get(POST.GIG_ID),
                r.get(POST.DAY),
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
