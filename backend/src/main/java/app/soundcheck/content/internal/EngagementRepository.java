package app.soundcheck.content.internal;

import static app.soundcheck.shared.jooq.content.Tables.COMMENT;
import static app.soundcheck.shared.jooq.content.Tables.POST;
import static app.soundcheck.shared.jooq.content.Tables.POST_HIDE;
import static app.soundcheck.shared.jooq.content.Tables.POST_LIKE;
import static app.soundcheck.shared.jooq.content.Tables.REPORT;

import app.soundcheck.shared.web.Cursor;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** jOOQ access to likes, comments, hides and reports ({@code content.*}). */
@Repository
public class EngagementRepository {

    /** A comment row; {@code deletedAt} is set for soft-deleted comments. */
    public record CommentRow(
            UUID id, UUID postId, UUID authorId, String body, Instant createdAt, @Nullable Instant deletedAt) {}

    private final DSLContext dsl;

    EngagementRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ── likes ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Adds the like and, only if a row was really inserted, increments the post's counter in the same
     * transaction. The counter update is a single {@code like_count = like_count + 1} statement, which the
     * database applies atomically: fifty concurrent likes cannot lose an increment.
     *
     * @return true when this call added the like
     */
    public boolean like(UUID postId, UUID userId, Instant now) {
        int inserted = dsl.insertInto(POST_LIKE)
                .set(POST_LIKE.POST_ID, postId)
                .set(POST_LIKE.USER_ID, userId)
                .set(POST_LIKE.CREATED_AT, at(now))
                .onConflictDoNothing()
                .execute();
        if (inserted == 0) {
            return false;
        }
        dsl.update(POST)
                .set(POST.LIKE_COUNT, POST.LIKE_COUNT.plus(1))
                .where(POST.ID.eq(postId))
                .execute();
        return true;
    }

    /** Removes the like and decrements the counter, only if there was one. */
    public boolean unlike(UUID postId, UUID userId) {
        int deleted = dsl.deleteFrom(POST_LIKE)
                .where(POST_LIKE.POST_ID.eq(postId))
                .and(POST_LIKE.USER_ID.eq(userId))
                .execute();
        if (deleted == 0) {
            return false;
        }
        dsl.update(POST)
                .set(POST.LIKE_COUNT, POST.LIKE_COUNT.minus(1))
                .where(POST.ID.eq(postId))
                .execute();
        return true;
    }

    public int likeCount(UUID postId) {
        Integer count =
                dsl.select(POST.LIKE_COUNT).from(POST).where(POST.ID.eq(postId)).fetchOne(POST.LIKE_COUNT);
        return count == null ? 0 : count;
    }

    public boolean liked(UUID postId, UUID userId) {
        return dsl.fetchExists(POST_LIKE, POST_LIKE.POST_ID.eq(postId).and(POST_LIKE.USER_ID.eq(userId)));
    }

    // ── comments ─────────────────────────────────────────────────────────────────────────────────

    public void addComment(CommentRow comment) {
        dsl.insertInto(COMMENT)
                .set(COMMENT.ID, comment.id())
                .set(COMMENT.POST_ID, comment.postId())
                .set(COMMENT.AUTHOR_ID, comment.authorId())
                .set(COMMENT.BODY, comment.body())
                .set(COMMENT.CREATED_AT, at(comment.createdAt()))
                .execute();
        dsl.update(POST)
                .set(POST.COMMENT_COUNT, POST.COMMENT_COUNT.plus(1))
                .where(POST.ID.eq(comment.postId()))
                .execute();
    }

    public Optional<CommentRow> comment(UUID id) {
        return dsl.selectFrom(COMMENT)
                .where(COMMENT.ID.eq(id))
                .fetchOptional(r -> new CommentRow(
                        r.getId(),
                        r.getPostId(),
                        r.getAuthorId(),
                        r.getBody(),
                        r.getCreatedAt().toInstant(),
                        r.getDeletedAt() == null ? null : r.getDeletedAt().toInstant()));
    }

    /**
     * A post's comments, oldest first, without the deleted ones; {@code limit + 1} rows. Expected index:
     * {@code comment_post (post_id, created_at, id)}.
     */
    public List<CommentRow> comments(UUID postId, @Nullable Cursor cursor, int limit) {
        Condition where = COMMENT.POST_ID.eq(postId).and(COMMENT.DELETED_AT.isNull());
        if (cursor != null) {
            where = where.and(DSL.row(COMMENT.CREATED_AT, COMMENT.ID).gt(DSL.row(at(cursor.at()), cursor.id())));
        }
        return dsl.selectFrom(COMMENT)
                .where(where)
                .orderBy(COMMENT.CREATED_AT.asc(), COMMENT.ID.asc())
                .limit(limit + 1)
                .fetch(r -> new CommentRow(
                        r.getId(),
                        r.getPostId(),
                        r.getAuthorId(),
                        r.getBody(),
                        r.getCreatedAt().toInstant(),
                        null));
    }

    /** Soft delete; the counter goes down only if this call deleted it. */
    public boolean deleteComment(UUID commentId, UUID postId, Instant now) {
        int updated = dsl.update(COMMENT)
                .set(COMMENT.DELETED_AT, at(now))
                .where(COMMENT.ID.eq(commentId))
                .and(COMMENT.DELETED_AT.isNull())
                .execute();
        if (updated == 0) {
            return false;
        }
        dsl.update(POST)
                .set(POST.COMMENT_COUNT, POST.COMMENT_COUNT.minus(1))
                .where(POST.ID.eq(postId))
                .execute();
        return true;
    }

    // ── hide and report ──────────────────────────────────────────────────────────────────────────

    public void hide(UUID postId, UUID userId, Instant now) {
        dsl.insertInto(POST_HIDE)
                .set(POST_HIDE.POST_ID, postId)
                .set(POST_HIDE.USER_ID, userId)
                .set(POST_HIDE.CREATED_AT, at(now))
                .onConflictDoNothing()
                .execute();
    }

    /** One report per user and post: a repeat changes nothing. */
    public void reportPost(
            UUID id, UUID postId, UUID reporterId, String reason, @Nullable String details, Instant now) {
        dsl.insertInto(REPORT)
                .set(REPORT.ID, id)
                .set(REPORT.POST_ID, postId)
                .set(REPORT.REPORTER_ID, reporterId)
                .set(REPORT.REASON, reason)
                .set(REPORT.DETAILS, details)
                .set(REPORT.CREATED_AT, at(now))
                .onConflictDoNothing()
                .execute();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
