package app.soundcheck.content.internal;

import static app.soundcheck.shared.jooq.content.Tables.COMMENT;
import static app.soundcheck.shared.jooq.content.Tables.GIG_COMMENT;
import static app.soundcheck.shared.jooq.content.Tables.REPORT;

import app.soundcheck.shared.jooq.content.tables.records.ReportRecord;
import app.soundcheck.shared.web.Cursor;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/**
 * jOOQ access to {@code content.report}: fans' reports on a post, a post comment or
 * a gig comment, the console's queue of them grouped by what they report, and their resolution. Also the
 * few comment columns the queue's previews show.
 */
@Repository
public class ReportRepository {

    /** What a report is about. Exactly one of a report's three target columns is set (report_one_target). */
    public enum Kind {
        POST,
        COMMENT,
        GIG_COMMENT
    }

    /** A report's target: its kind and the id of the post or comment. */
    public record Target(Kind kind, UUID id) {}

    /** One report row. {@code action}, {@code resolvedAt} and {@code resolvedBy} are null while OPEN. */
    public record ReportRow(
            UUID id,
            Target target,
            UUID reporterId,
            String reason,
            @Nullable String details,
            String status,
            Instant createdAt,
            @Nullable String action,
            @Nullable Instant resolvedAt,
            @Nullable UUID resolvedBy) {}

    /** A target with its reports counted: one row of the queue, before its previews are added. */
    public record Group(
            Target target,
            int reportCount,
            Instant firstReportedAt,
            Instant lastReportedAt,
            @Nullable Instant lastResolvedAt) {}

    /** A post comment or a gig comment as the queue previews it; {@code parentId} is the post or the gig. */
    public record CommentRow(UUID id, UUID parentId, UUID authorId, String body, Instant createdAt, boolean deleted) {}

    private final DSLContext dsl;

    ReportRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ── reporting ────────────────────────────────────────────────────────────────────────────────

    /**
     * One report per user and target: a repeat changes nothing (the unique indexes report_once_post,
     * report_once_comment and report_once_gig_comment decide, so two at once cannot both land).
     *
     * @return true when this call added the report
     */
    public boolean insert(
            UUID id, Target target, UUID reporterId, String reason, @Nullable String details, Instant now) {
        return dsl.insertInto(REPORT)
                        .set(REPORT.ID, id)
                        .set(column(target.kind()), target.id())
                        .set(REPORT.REPORTER_ID, reporterId)
                        .set(REPORT.REASON, reason)
                        .set(REPORT.DETAILS, details)
                        .set(REPORT.CREATED_AT, at(now))
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    // ── the queue ────────────────────────────────────────────────────────────────────────────────

    /**
     * One page of targets with OPEN reports, the longest-waiting first: {@code (firstReportedAt, target)}
     * ascending, {@code limit + 1} rows. Grouping needs an aggregate over every OPEN report, read through
     * {@code report_open (created_at) where status = 'OPEN'}; at this scale (hundreds of open reports at
     * most) that is a few milliseconds, and the keyset sits in HAVING because it is on the aggregate.
     */
    public List<Group> openGroups(@Nullable Cursor cursor, int limit) {
        Field<OffsetDateTime> first = DSL.min(REPORT.CREATED_AT);
        Condition after = cursor == null
                ? DSL.noCondition()
                : DSL.row(first, targetId()).gt(DSL.row(at(cursor.at()), cursor.id()));
        return groups(REPORT.STATUS.eq("OPEN"), after, first.asc(), targetId().asc(), limit);
    }

    /**
     * One page of targets with resolved reports, the latest decision first: {@code (lastResolvedAt,
     * target)} descending, {@code limit + 1} rows. Expected index: {@code report_resolved (resolved_at
     * desc) where status <> 'OPEN'}; the same aggregate as {@link #openGroups}, over resolved reports.
     */
    public List<Group> resolvedGroups(@Nullable Cursor cursor, int limit) {
        Field<OffsetDateTime> resolved = DSL.max(REPORT.RESOLVED_AT);
        Condition before = cursor == null
                ? DSL.noCondition()
                : DSL.row(resolved, targetId()).lt(DSL.row(at(cursor.at()), cursor.id()));
        return groups(
                REPORT.STATUS.ne("OPEN"), before, resolved.desc(), targetId().desc(), limit);
    }

    /** The reports matching {@code where}, one row per target (exactly one target column is set). */
    private List<Group> groups(Condition where, Condition having, SortField<?> by, SortField<?> then, int limit) {
        return dsl.select(
                        REPORT.POST_ID,
                        REPORT.COMMENT_ID,
                        REPORT.GIG_COMMENT_ID,
                        DSL.count(),
                        DSL.min(REPORT.CREATED_AT),
                        DSL.max(REPORT.CREATED_AT),
                        DSL.max(REPORT.RESOLVED_AT))
                .from(REPORT)
                .where(where)
                .groupBy(REPORT.POST_ID, REPORT.COMMENT_ID, REPORT.GIG_COMMENT_ID)
                .having(having)
                .orderBy(by, then)
                .limit(limit + 1)
                .fetch(r -> {
                    OffsetDateTime resolved = r.value7();
                    return new Group(
                            target(r.value1(), r.value2(), r.value3()),
                            r.value4(),
                            r.value5().toInstant(),
                            r.value6().toInstant(),
                            resolved == null ? null : resolved.toInstant());
                });
    }

    /**
     * Every report, OPEN or resolved as asked, on the targets, newest first: their reasons, details and
     * resolutions. Expected indexes: report_post, report_comment, report_gig_comment (V143).
     */
    public List<ReportRow> reportsOn(Collection<Target> targets, boolean open) {
        if (targets.isEmpty()) {
            return List.of();
        }
        Condition onTarget =
                DSL.or(targets.stream().map(t -> column(t.kind()).eq(t.id())).toList());
        return dsl.selectFrom(REPORT)
                .where(onTarget)
                .and(open ? REPORT.STATUS.eq("OPEN") : REPORT.STATUS.ne("OPEN"))
                .orderBy(REPORT.CREATED_AT.desc(), REPORT.ID.desc())
                .fetch(ReportRepository::toRow);
    }

    public Optional<ReportRow> find(UUID reportId) {
        return dsl.selectFrom(REPORT).where(REPORT.ID.eq(reportId)).fetchOptional(ReportRepository::toRow);
    }

    // ── resolving ────────────────────────────────────────────────────────────────────────────────

    /**
     * The target's OPEN reports, locked until the transaction ends: two moderators resolving the same
     * target one after the other find nothing left the second time. Expected index: report_post,
     * report_comment or report_gig_comment.
     */
    public List<UUID> lockOpen(Target target) {
        return dsl.select(REPORT.ID)
                .from(REPORT)
                .where(column(target.kind()).eq(target.id()))
                .and(REPORT.STATUS.eq("OPEN"))
                .forUpdate()
                .fetch(REPORT.ID);
    }

    /** Resolves every OPEN report on the target with one decision; returns how many. */
    public int resolve(Target target, String status, String action, UUID moderatorId, Instant now) {
        return dsl.update(REPORT)
                .set(REPORT.STATUS, status)
                .set(REPORT.ACTION, action)
                .set(REPORT.RESOLVED_AT, at(now))
                .set(REPORT.RESOLVED_BY, moderatorId)
                .where(column(target.kind()).eq(target.id()))
                .and(REPORT.STATUS.eq("OPEN"))
                .execute();
    }

    // ── previews ─────────────────────────────────────────────────────────────────────────────────

    /** Post comments by id, deleted ones included (marked). Expected index: the comment primary key. */
    public Map<UUID, CommentRow> comments(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return dsl.selectFrom(COMMENT)
                .where(COMMENT.ID.in(ids))
                .fetchMap(
                        COMMENT.ID,
                        r -> new CommentRow(
                                r.getId(),
                                r.getPostId(),
                                r.getAuthorId(),
                                r.getBody(),
                                r.getCreatedAt().toInstant(),
                                r.getDeletedAt() != null));
    }

    /** Gig comments by id, deleted ones included (marked). Expected index: the gig_comment primary key. */
    public Map<UUID, CommentRow> gigComments(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return dsl.selectFrom(GIG_COMMENT)
                .where(GIG_COMMENT.ID.in(ids))
                .fetchMap(
                        GIG_COMMENT.ID,
                        r -> new CommentRow(
                                r.getId(),
                                r.getGigId(),
                                r.getAuthorId(),
                                r.getBody(),
                                r.getCreatedAt().toInstant(),
                                r.getDeletedAt() != null));
    }

    // ── mapping ──────────────────────────────────────────────────────────────────────────────────

    private static TableField<?, UUID> column(Kind kind) {
        return switch (kind) {
            case POST -> REPORT.POST_ID;
            case COMMENT -> REPORT.COMMENT_ID;
            case GIG_COMMENT -> REPORT.GIG_COMMENT_ID;
        };
    }

    /** The target's id whichever column holds it; ids are UUIDv7, so they never clash across tables. */
    private static Field<UUID> targetId() {
        return DSL.coalesce(REPORT.POST_ID, REPORT.COMMENT_ID, REPORT.GIG_COMMENT_ID);
    }

    private static Target target(@Nullable UUID postId, @Nullable UUID commentId, @Nullable UUID gigCommentId) {
        if (postId != null) {
            return new Target(Kind.POST, postId);
        }
        if (commentId != null) {
            return new Target(Kind.COMMENT, commentId);
        }
        return new Target(Kind.GIG_COMMENT, gigCommentId);
    }

    private static ReportRow toRow(ReportRecord r) {
        return new ReportRow(
                r.getId(),
                target(r.getPostId(), r.getCommentId(), r.getGigCommentId()),
                r.getReporterId(),
                r.getReason(),
                r.getDetails(),
                r.getStatus(),
                r.getCreatedAt().toInstant(),
                r.getAction(),
                r.getResolvedAt() == null ? null : r.getResolvedAt().toInstant(),
                r.getResolvedBy());
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
