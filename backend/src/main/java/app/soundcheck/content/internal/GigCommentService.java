package app.soundcheck.content.internal;

import static app.soundcheck.shared.jooq.content.Tables.GIG_COMMENT;

import app.soundcheck.catalog.Catalog;
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
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The comment thread under a gig. Anyone who can see the gig (every gig in the catalog)
 * can comment; the limits are a post comment's (1-500 characters, an optional Idempotency-Key). Nobody
 * is notified. What a viewer sees follows the post comments' rules: nothing from someone with a block
 * between them and the viewer; and a comment from a private profile the viewer does not follow
 * shows only that it is there ("a private profile"), without who wrote it or what it says.
 */
@Service
@Transactional
public class GigCommentService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    /**
     * A gig comment as the API shows it. {@code fromPrivateProfile}: written by a private profile the
     * viewer does not follow, so {@code author} and {@code body} are null.
     */
    public record GigCommentView(
            UUID id,
            UUID gigId,
            @Schema(nullable = true) UserSummary author,
            @Schema(nullable = true) String body,
            Instant createdAt,
            boolean fromPrivateProfile) {}

    private record Row(
            UUID id, UUID gigId, UUID authorId, String body, Instant createdAt, @Nullable Instant deletedAt) {}

    private final DSLContext dsl;
    private final EngagementService engagement;
    private final Catalog catalog;
    private final Users users;
    private final SocialGraph social;
    private final AuthorVisibility visibility;
    private final IdempotencyKeys idempotency;
    private final JsonMapper json;
    private final Clock clock;

    GigCommentService(
            DSLContext dsl,
            EngagementService engagement,
            Catalog catalog,
            Users users,
            SocialGraph social,
            AuthorVisibility visibility,
            IdempotencyKeys idempotency,
            JsonMapper json,
            Clock clock) {
        this.dsl = dsl;
        this.engagement = engagement;
        this.catalog = catalog;
        this.users = users;
        this.social = social;
        this.visibility = visibility;
        this.idempotency = idempotency;
        this.json = json;
        this.clock = clock;
    }

    public GigCommentView comment(UUID userId, UUID gigId, String body, @Nullable String idempotencyKey) {
        String text = body.strip();
        if (idempotencyKey != null) {
            String textJson;
            try {
                textJson = json.writeValueAsString(text);
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
            Optional<String> remembered =
                    idempotency.begin(userId, idempotencyKey, "POST /gigs/" + gigId + "/comments " + textJson);
            if (remembered.isPresent()) {
                String rememberedCommentId;
                try {
                    rememberedCommentId =
                            json.readTree(remembered.get()).path("commentId").asText("");
                } catch (JsonProcessingException e) {
                    throw new UncheckedIOException(e);
                }
                UUID commentId = UUID.fromString(rememberedCommentId);
                // A retry after the comment was deleted finds nothing, like any other read of it.
                return own(find(commentId)
                        .filter(c -> c.deletedAt() == null)
                        .orElseThrow(GigCommentService::commentNotFound));
            }
        }
        if (!catalog.gigExists(gigId)) {
            throw gigNotFound(gigId);
        }
        Row row = new Row(Ids.newId(), gigId, userId, text, Instant.now(clock), null);
        dsl.insertInto(GIG_COMMENT)
                .set(GIG_COMMENT.ID, row.id())
                .set(GIG_COMMENT.GIG_ID, gigId)
                .set(GIG_COMMENT.AUTHOR_ID, userId)
                .set(GIG_COMMENT.BODY, text)
                .set(GIG_COMMENT.CREATED_AT, at(row.createdAt()))
                .execute();
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
        return own(row);
    }

    /** Oldest first, like a post's. Expected index: gig_comment_gig (gig_id, created_at, id). */
    @Transactional(readOnly = true)
    public Page<GigCommentView> comments(UUID viewerId, UUID gigId, @Nullable String cursor, @Nullable Integer limit) {
        if (!catalog.gigExists(gigId)) {
            throw gigNotFound(gigId);
        }
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        Cursor after = Cursor.decode(cursor);
        Condition where = GIG_COMMENT.GIG_ID.eq(gigId).and(GIG_COMMENT.DELETED_AT.isNull());
        if (after != null) {
            where = where.and(DSL.row(GIG_COMMENT.CREATED_AT, GIG_COMMENT.ID).gt(DSL.row(at(after.at()), after.id())));
        }
        List<Row> rows = dsl.selectFrom(GIG_COMMENT)
                .where(where)
                .orderBy(GIG_COMMENT.CREATED_AT.asc(), GIG_COMMENT.ID.asc())
                .limit(size + 1)
                .fetch(r -> new Row(
                        r.getId(),
                        r.getGigId(),
                        r.getAuthorId(),
                        r.getBody(),
                        r.getCreatedAt().toInstant(),
                        null));
        boolean more = rows.size() > size;
        List<Row> shown = more ? rows.subList(0, size) : rows;
        List<UUID> authorIds = shown.stream().map(Row::authorId).distinct().toList();
        Map<UUID, UserSummary> authors = users.summaries(authorIds);
        Set<UUID> blocked = social.blockedAmong(viewerId, authors.keySet());
        Set<UUID> hidden = visibility.hiddenAmong(viewerId, authors.keySet());
        List<GigCommentView> views = shown.stream()
                .map(row -> {
                    UserSummary author = authors.get(row.authorId());
                    if (author == null || blocked.contains(row.authorId())) {
                        return null; // an account that is gone, or a block either way
                    }
                    return hidden.contains(row.authorId())
                            ? new GigCommentView(row.id(), row.gigId(), null, null, row.createdAt(), true)
                            : new GigCommentView(row.id(), row.gigId(), author, row.body(), row.createdAt(), false);
                })
                .filter(Objects::nonNull)
                .toList();
        String next =
                more ? new Cursor(shown.getLast().createdAt(), shown.getLast().id()).encode() : null;
        return new Page<>(views, next);
    }

    /** Its author or an admin; to anyone else it does not exist. Soft delete, idempotent for the author. */
    public void delete(UUID actorId, boolean admin, UUID commentId) {
        Row comment =
                find(commentId).filter(c -> c.deletedAt() == null).orElseThrow(GigCommentService::commentNotFound);
        if (!admin && !comment.authorId().equals(actorId)) {
            throw commentNotFound();
        }
        dsl.update(GIG_COMMENT)
                .set(GIG_COMMENT.DELETED_AT, at(Instant.now(clock)))
                .where(GIG_COMMENT.ID.eq(commentId))
                .and(GIG_COMMENT.DELETED_AT.isNull())
                .execute();
    }

    /**
     * A report on a gig comment: the comment must be there for the reporter (not deleted, by a
     * live account, no block either way); anything else is 404, as reading it would be. One per user and
     * comment; a repeat changes nothing.
     */
    public void report(UUID userId, UUID commentId, String reason, @Nullable String details) {
        Row comment = find(commentId)
                .filter(c -> c.deletedAt() == null)
                .filter(c -> c.authorId().equals(userId)
                        || (users.exists(c.authorId()) && !social.blockBetween(userId, c.authorId())))
                .orElseThrow(GigCommentService::commentNotFound);
        engagement.file(
                userId, new ReportRepository.Target(ReportRepository.Kind.GIG_COMMENT, comment.id()), reason, details);
    }

    private Optional<Row> find(UUID id) {
        return dsl.selectFrom(GIG_COMMENT)
                .where(GIG_COMMENT.ID.eq(id))
                .fetchOptional(r -> new Row(
                        r.getId(),
                        r.getGigId(),
                        r.getAuthorId(),
                        r.getBody(),
                        r.getCreatedAt().toInstant(),
                        r.getDeletedAt() == null ? null : r.getDeletedAt().toInstant()));
    }

    /** The writer's own comment: always shown to them in full. */
    private GigCommentView own(Row row) {
        UserSummary author = users.summary(row.authorId()).orElseThrow(GigCommentService::commentNotFound);
        return new GigCommentView(row.id(), row.gigId(), author, row.body(), row.createdAt(), false);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static ApiException commentNotFound() {
        return ApiException.notFound("COMMENT_NOT_FOUND", "No such comment");
    }

    private static ApiException gigNotFound(UUID gigId) {
        return ApiException.notFound("GIG_NOT_FOUND", "No gig " + gigId);
    }
}
