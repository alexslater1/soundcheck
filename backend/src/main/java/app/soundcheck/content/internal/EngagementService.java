package app.soundcheck.content.internal;

import app.soundcheck.content.CommentAdded;
import app.soundcheck.content.PostLiked;
import app.soundcheck.content.internal.EngagementRepository.CommentRow;
import app.soundcheck.identity.UserSummary;
import app.soundcheck.identity.Users;
import app.soundcheck.shared.Ids;
import app.soundcheck.shared.idempotency.IdempotencyKeys;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Cursor;
import app.soundcheck.shared.web.Page;
import app.soundcheck.social.SocialGraph;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Likes, comments, "show me less" and reports. Everything here acts on a PUBLISHED post; to
 * anything else the post does not exist. Likes and hides are idempotent, and a counter only moves when a
 * row was really inserted or deleted, in the same transaction.
 */
@Service
@Transactional
public class EngagementService {

    public static final Set<String> REPORT_REASONS =
            Set.of("SPAM", "ABUSE", "NUDITY", "VIOLENCE", "COPYRIGHT", "OTHER");
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    /** The state of the like button after a change. */
    public record LikeState(int likeCount, boolean likedByMe) {}

    /** A comment as the API shows it. */
    public record CommentView(UUID id, UUID postId, UserSummary author, String body, Instant createdAt) {}

    private final EngagementRepository engagement;
    private final PostRepository posts;
    private final Users users;
    private final SocialGraph social;
    private final IdempotencyKeys idempotency;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final Clock clock;

    EngagementService(
            EngagementRepository engagement,
            PostRepository posts,
            Users users,
            SocialGraph social,
            IdempotencyKeys idempotency,
            ApplicationEventPublisher events,
            JsonMapper json,
            Clock clock) {
        this.engagement = engagement;
        this.posts = posts;
        this.users = users;
        this.social = social;
        this.idempotency = idempotency;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    // ── likes ────────────────────────────────────────────────────────────────────────────────────

    public LikeState like(UUID userId, UUID postId) {
        PostRow post = visible(userId, postId);
        if (engagement.like(postId, userId, Instant.now(clock))) {
            events.publishEvent(new PostLiked(postId, post.authorId(), userId));
        }
        return new LikeState(engagement.likeCount(postId), true);
    }

    public LikeState unlike(UUID userId, UUID postId) {
        visible(userId, postId);
        engagement.unlike(postId, userId);
        return new LikeState(engagement.likeCount(postId), false);
    }

    // ── comments ─────────────────────────────────────────────────────────────────────────────────

    public CommentView comment(UUID userId, UUID postId, String body, @Nullable String idempotencyKey) {
        String text = body.strip();
        if (idempotencyKey != null) {
            String textJson;
            try {
                textJson = json.writeValueAsString(text);
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
            Optional<String> remembered =
                    idempotency.begin(userId, idempotencyKey, "POST /posts/" + postId + "/comments " + textJson);
            if (remembered.isPresent()) {
                String rememberedCommentId;
                try {
                    rememberedCommentId =
                            json.readTree(remembered.get()).path("commentId").asText("");
                } catch (JsonProcessingException e) {
                    throw new UncheckedIOException(e);
                }
                UUID commentId = UUID.fromString(rememberedCommentId);
                return view(engagement.comment(commentId).orElseThrow(EngagementService::commentNotFound));
            }
        }
        PostRow post = visible(userId, postId);
        CommentRow row = new CommentRow(Ids.newId(), postId, userId, text, Instant.now(clock), null);
        engagement.addComment(row);
        events.publishEvent(new CommentAdded(postId, post.authorId(), row.id(), userId));
        if (idempotencyKey != null) {
            String responseJson;
            try {
                responseJson =
                        json.writeValueAsString(Map.of("commentId", row.id().toString()));
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
            idempotency.complete(userId, idempotencyKey, 201, responseJson);
        }
        return view(row);
    }

    @Transactional(readOnly = true)
    public Page<CommentView> comments(UUID viewerId, UUID postId, @Nullable String cursor, @Nullable Integer limit) {
        visible(viewerId, postId);
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        List<CommentRow> rows = engagement.comments(postId, Cursor.decode(cursor), size);
        boolean more = rows.size() > size;
        List<CommentRow> shown = more ? rows.subList(0, size) : rows;
        Map<UUID, UserSummary> authors = users.summaries(
                shown.stream().map(CommentRow::authorId).distinct().toList());
        // Comments by people with a block between them and the viewer are not shown to the viewer.
        Set<UUID> blocked = social.blockedAmong(viewerId, authors.keySet());
        List<CommentView> views = shown.stream()
                .map(row -> authors.containsKey(row.authorId()) && !blocked.contains(row.authorId())
                        ? new CommentView(
                                row.id(), row.postId(), authors.get(row.authorId()), row.body(), row.createdAt())
                        : null)
                .filter(Objects::nonNull) // a commenter whose account is gone
                .toList();
        String next =
                more ? new Cursor(shown.getLast().createdAt(), shown.getLast().id()).encode() : null;
        return new Page<>(views, next);
    }

    /** The comment's author, the post's author, or an admin; to anyone else it does not exist. */
    public void deleteComment(UUID actorId, boolean admin, UUID commentId) {
        CommentRow comment = engagement
                .comment(commentId)
                .filter(c -> c.deletedAt() == null)
                .orElseThrow(EngagementService::commentNotFound);
        boolean allowed = admin
                || comment.authorId().equals(actorId)
                || posts.find(comment.postId())
                        .filter(p -> p.authorId().equals(actorId))
                        .isPresent();
        if (!allowed) {
            throw commentNotFound();
        }
        engagement.deleteComment(comment.id(), comment.postId(), Instant.now(clock));
    }

    // ── hide and report ──────────────────────────────────────────────────────────────────────────

    /** "Show me less": the post is left out of this user's feeds from now on. */
    public void hide(UUID userId, UUID postId) {
        visible(userId, postId);
        engagement.hide(postId, userId, Instant.now(clock));
    }

    public void report(UUID userId, UUID postId, String reason, @Nullable String details) {
        visible(userId, postId);
        if (!REPORT_REASONS.contains(reason)) {
            throw ApiException.badRequest("REPORT_REASON_INVALID", "reason must be one of " + REPORT_REASONS);
        }
        engagement.reportPost(
                Ids.newId(),
                postId,
                userId,
                reason,
                details == null || details.isBlank() ? null : details.strip(),
                Instant.now(clock));
    }

    /**
     * The post, if the actor may see it: published, and no block between the actor and its author
     * (one cannot like or comment on a blocker's posts; to them the post does not exist).
     */
    private PostRow visible(UUID actorId, UUID postId) {
        return posts.find(postId)
                .filter(PostRow::published)
                .filter(p -> p.authorId().equals(actorId) || !social.blockBetween(actorId, p.authorId()))
                .orElseThrow(PostService::notFound);
    }

    private CommentView view(CommentRow row) {
        UserSummary author = users.summary(row.authorId()).orElseThrow(EngagementService::commentNotFound);
        return new CommentView(row.id(), row.postId(), author, row.body(), row.createdAt());
    }

    static ApiException commentNotFound() {
        return ApiException.notFound("COMMENT_NOT_FOUND", "No such comment");
    }
}
