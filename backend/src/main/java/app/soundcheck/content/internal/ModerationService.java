package app.soundcheck.content.internal;

import app.soundcheck.catalog.CatalogViews;
import app.soundcheck.catalog.GigSummary;
import app.soundcheck.content.internal.ReportRepository.CommentRow;
import app.soundcheck.content.internal.ReportRepository.Group;
import app.soundcheck.content.internal.ReportRepository.Kind;
import app.soundcheck.content.internal.ReportRepository.ReportRow;
import app.soundcheck.content.internal.ReportRepository.Target;
import app.soundcheck.identity.UserSummary;
import app.soundcheck.identity.Users;
import app.soundcheck.media.MediaLibrary;
import app.soundcheck.media.Playback;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Cursor;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The console's moderation queue. Fans' reports are grouped by what they report, so
 * the operator decides once per post or comment however many people reported it: hide the post, delete
 * the comment, or dismiss the reports. One decision resolves every OPEN report on the target. A hide is
 * undone by {@link PostService#restore}; a deleted comment stays deleted.
 */
@Service
@Transactional
public class ModerationService {

    public static final Set<String> ACTIONS = Set.of("HIDE_POST", "DELETE_COMMENT", "DISMISS");
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    /** How many of a target's report texts the queue shows, newest first. */
    static final int DETAILS_SHOWN = 3;

    /** A gig, as far as a preview needs it. */
    public record GigRef(UUID id, String title, Instant startsAt) {}

    /** The post a comment was written under. */
    public record PostRef(
            UUID id,
            @Schema(nullable = true) String caption,
            @Schema(allowableValues = {"PENDING_MEDIA", "PUBLISHED", "FAILED", "HIDDEN", "DELETED"}) String status) {}

    /** A reported post: its cover clip (to play in the console), caption, author, gig and status now. */
    public record PostPreview(
            UUID id,
            @Schema(allowableValues = {"PENDING_MEDIA", "PUBLISHED", "FAILED", "HIDDEN", "DELETED"}) String status,
            @Schema(nullable = true) String caption,
            @Schema(nullable = true, description = "Null when the account is gone") UserSummary author,
            @Schema(nullable = true) GigRef gig,
            @Schema(nullable = true, description = "The first clip that has one") Playback playback,
            int clipCount) {}

    /** A reported comment in its context: under a post (post comment) or a gig (gig comment). */
    public record CommentPreview(
            UUID id,
            String body,
            @Schema(nullable = true, description = "Null when the account is gone") UserSummary author,
            Instant createdAt,
            boolean deleted,
            @Schema(nullable = true) PostRef post,
            @Schema(nullable = true) GigRef gig) {}

    /** How the reports on a target were resolved (the Resolved tab), and the target's status now. */
    public record Resolution(
            @Schema(allowableValues = {"HIDE_POST", "DELETE_COMMENT", "DISMISS"}) String action,
            Instant resolvedAt,
            @Schema(nullable = true, description = "The moderator's handle") String resolvedBy,
            @Schema(
                            description = "A post's status now, or VISIBLE / DELETED for a comment",
                            allowableValues = {
                                "PENDING_MEDIA",
                                "PUBLISHED",
                                "FAILED",
                                "HIDDEN",
                                "DELETED",
                                "VISIBLE",
                                "GONE"
                            })
                    String targetStatus) {}

    /**
     * One row of the queue: a target and its reports. {@code id} is the target's; {@code reportId} is one
     * of its reports, the handle {@code resolve} takes. Exactly one of {@code post} and {@code comment}.
     */
    public record ReportItem(
            @Schema(allowableValues = {"POST", "COMMENT", "GIG_COMMENT"}) String kind,
            UUID id,
            UUID reportId,
            int reportCount,
            @Schema(description = "Reason → how many reports gave it") Map<String, Integer> reasons,
            @Schema(description = "Up to three report texts, newest first") List<String> latestDetails,
            Instant firstReportedAt,
            Instant lastReportedAt,
            @Schema(nullable = true) PostPreview post,
            @Schema(nullable = true) CommentPreview comment,
            @Schema(nullable = true, description = "Present on the Resolved tab") Resolution resolution) {}

    /** What a decision changed. */
    public record Resolved(
            @Schema(allowableValues = {"POST", "COMMENT", "GIG_COMMENT"}) String kind,
            UUID id,
            @Schema(allowableValues = {"HIDE_POST", "DELETE_COMMENT", "DISMISS"}) String action,
            int resolvedReports) {}

    private final ReportRepository reports;
    private final PostRepository posts;
    private final ClipRepository clips;
    private final PostService postService;
    private final EngagementService engagement;
    private final GigCommentService gigComments;
    private final Users users;
    private final CatalogViews catalog;
    private final MediaLibrary media;
    private final Clock clock;

    ModerationService(
            ReportRepository reports,
            PostRepository posts,
            ClipRepository clips,
            PostService postService,
            EngagementService engagement,
            GigCommentService gigComments,
            Users users,
            CatalogViews catalog,
            MediaLibrary media,
            Clock clock) {
        this.reports = reports;
        this.posts = posts;
        this.clips = clips;
        this.postService = postService;
        this.engagement = engagement;
        this.gigComments = gigComments;
        this.users = users;
        this.catalog = catalog;
        this.media = media;
        this.clock = clock;
    }

    // ── the queue ────────────────────────────────────────────────────────────────────────────────

    /**
     * One page of the queue. OPEN: targets with open reports, the longest-waiting first. Resolved: targets
     * whose reports were resolved, the latest decision first, with the decision and the target's status
     * now (a restored post reads PUBLISHED again).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ) // one snapshot for its queries
    public Page<ReportItem> queue(UUID moderatorId, boolean open, @Nullable String cursor, @Nullable Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        Cursor from = Cursor.decode(cursor);
        List<Group> groups = open ? reports.openGroups(from, size) : reports.resolvedGroups(from, size);
        boolean more = groups.size() > size;
        List<Group> shown = more ? groups.subList(0, size) : groups;

        Map<Target, List<ReportRow>> rowsOf = new LinkedHashMap<>();
        for (ReportRow row : reports.reportsOn(shown.stream().map(Group::target).toList(), open)) {
            rowsOf.computeIfAbsent(row.target(), t -> new ArrayList<>()).add(row);
        }
        Previews previews =
                previews(moderatorId, shown.stream().map(Group::target).toList());
        Map<UUID, UserSummary> moderators = open
                ? Map.of()
                : users.summaries(rowsOf.values().stream()
                        .flatMap(List::stream)
                        .map(ReportRow::resolvedBy)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());

        List<ReportItem> items = shown.stream()
                .map(group -> item(group, rowsOf.getOrDefault(group.target(), List.of()), previews, moderators))
                .toList();
        String next = null;
        if (more) {
            Group last = shown.getLast();
            Instant at = open ? last.firstReportedAt() : Objects.requireNonNull(last.lastResolvedAt());
            next = new Cursor(at, last.target().id()).encode();
        }
        return new Page<>(items, next);
    }

    private ReportItem item(Group group, List<ReportRow> rows, Previews previews, Map<UUID, UserSummary> moderators) {
        Target target = group.target();
        // Rows are newest first: the reasons counted, the latest texts, and the latest decision.
        Map<String, Integer> reasons = new LinkedHashMap<>();
        rows.stream()
                .collect(Collectors.groupingBy(ReportRow::reason, Collectors.summingInt(r -> 1)))
                .entrySet()
                .stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue()
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> reasons.put(e.getKey(), e.getValue()));
        List<String> details = rows.stream()
                .map(ReportRow::details)
                .filter(Objects::nonNull)
                .limit(DETAILS_SHOWN)
                .toList();
        PostPreview post = target.kind() == Kind.POST ? previews.posts().get(target.id()) : null;
        CommentPreview comment =
                target.kind() == Kind.POST ? null : previews.comments().get(target.id());
        Resolution resolution = rows.stream()
                .filter(r -> r.resolvedAt() != null)
                .max((a, b) -> a.resolvedAt().compareTo(b.resolvedAt()))
                .map(r -> new Resolution(
                        Objects.requireNonNull(r.action()),
                        Objects.requireNonNull(r.resolvedAt()),
                        r.resolvedBy() == null || !moderators.containsKey(r.resolvedBy())
                                ? null
                                : moderators.get(r.resolvedBy()).handle(),
                        targetStatus(post, comment)))
                .orElse(null);
        return new ReportItem(
                target.kind().name(),
                target.id(),
                rows.isEmpty() ? target.id() : rows.getLast().id(),
                group.reportCount(),
                reasons,
                details,
                group.firstReportedAt(),
                group.lastReportedAt(),
                post,
                comment,
                resolution);
    }

    private static String targetStatus(@Nullable PostPreview post, @Nullable CommentPreview comment) {
        if (post != null) {
            return post.status();
        }
        if (comment != null) {
            return comment.deleted() ? "DELETED" : "VISIBLE";
        }
        return "GONE"; // the row itself is gone (an account purge removes it with its reports)
    }

    private record Previews(Map<UUID, PostPreview> posts, Map<UUID, CommentPreview> comments) {}

    /** Everything the targets' previews show, a fixed number of queries whatever the page holds. */
    private Previews previews(UUID moderatorId, Collection<Target> targets) {
        Map<UUID, CommentRow> postComments = reports.comments(ids(targets, Kind.COMMENT));
        Map<UUID, CommentRow> gigCommentRows = reports.gigComments(ids(targets, Kind.GIG_COMMENT));

        Set<UUID> postIds = new HashSet<>(ids(targets, Kind.POST));
        postComments.values().forEach(c -> postIds.add(c.parentId()));
        Map<UUID, PostRow> postRows = posts.findAll(postIds).stream().collect(Collectors.toMap(PostRow::id, p -> p));

        Map<UUID, List<ClipRow>> clipsOf = clips.of(ids(targets, Kind.POST));
        Map<UUID, Playback> playbacks = media.playbacks(clipsOf.values().stream()
                .flatMap(List::stream)
                .map(ClipRow::mediaId)
                .filter(Objects::nonNull)
                .toList());

        Set<UUID> gigIds = new HashSet<>();
        postRows.values().stream().map(PostRow::gigId).filter(Objects::nonNull).forEach(gigIds::add);
        gigCommentRows.values().forEach(c -> gigIds.add(c.parentId()));
        Map<UUID, GigSummary> gigs = catalog.gigs(moderatorId, gigIds);

        Map<UUID, UserSummary> authors = users.summaries(Stream.concat(
                        postRows.values().stream().map(PostRow::authorId),
                        Stream.concat(postComments.values().stream(), gigCommentRows.values().stream())
                                .map(CommentRow::authorId))
                .distinct()
                .toList());

        Map<UUID, PostPreview> postPreviews = new LinkedHashMap<>();
        for (UUID id : ids(targets, Kind.POST)) {
            PostRow row = postRows.get(id);
            if (row == null) {
                continue;
            }
            List<ClipRow> its = clipsOf.getOrDefault(id, List.of());
            Playback cover = its.stream()
                    .map(ClipRow::mediaId)
                    .filter(Objects::nonNull)
                    .map(playbacks::get)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            postPreviews.put(
                    id,
                    new PostPreview(
                            id,
                            row.status(),
                            row.caption(),
                            authors.get(row.authorId()),
                            gigRef(row.gigId(), gigs),
                            cover,
                            its.size()));
        }
        Map<UUID, CommentPreview> commentPreviews = new LinkedHashMap<>();
        postComments.values().forEach(c -> {
            PostRow under = postRows.get(c.parentId());
            commentPreviews.put(
                    c.id(),
                    new CommentPreview(
                            c.id(),
                            c.body(),
                            authors.get(c.authorId()),
                            c.createdAt(),
                            c.deleted(),
                            under == null ? null : new PostRef(under.id(), under.caption(), under.status()),
                            null));
        });
        gigCommentRows
                .values()
                .forEach(c -> commentPreviews.put(
                        c.id(),
                        new CommentPreview(
                                c.id(),
                                c.body(),
                                authors.get(c.authorId()),
                                c.createdAt(),
                                c.deleted(),
                                null,
                                gigRef(c.parentId(), gigs))));
        return new Previews(postPreviews, commentPreviews);
    }

    private static List<UUID> ids(Collection<Target> targets, Kind kind) {
        return targets.stream().filter(t -> t.kind() == kind).map(Target::id).toList();
    }

    private static @Nullable GigRef gigRef(@Nullable UUID gigId, Map<UUID, GigSummary> gigs) {
        GigSummary gig = gigId == null ? null : gigs.get(gigId);
        return gig == null ? null : new GigRef(gig.id(), gig.title(), gig.startsAt());
    }

    // ── decisions ────────────────────────────────────────────────────────────────────────────────

    /**
     * Resolves every OPEN report on the target of {@code reportId} with one decision, in one transaction:
     * HIDE_POST hides the post ({@link PostService#hide}), DELETE_COMMENT deletes the comment as an admin
     * delete would, DISMISS changes only the reports. The reports become ACTIONED (or DISMISSED), with the
     * action, the moderator and the time.
     *
     * @throws ApiException 404 REPORT_NOT_FOUND; 409 ACTION_DOES_NOT_FIT (hiding a comment, deleting a
     *     post); 409 REPORT_NOT_OPEN when no OPEN report is left on the target (resolved meanwhile)
     */
    public Resolved resolve(UUID moderatorId, UUID reportId, String action) {
        if (!ACTIONS.contains(action)) {
            throw ApiException.badRequest("ACTION_INVALID", "action must be one of " + ACTIONS);
        }
        Target target = reports.find(reportId)
                .map(ReportRow::target)
                .orElseThrow(() -> ApiException.notFound("REPORT_NOT_FOUND", "No such report"));
        if (!fits(action, target.kind())) {
            throw ApiException.conflict(
                    "ACTION_DOES_NOT_FIT",
                    action + " does not apply to a " + target.kind().name().toLowerCase());
        }
        if (reports.lockOpen(target).isEmpty()) {
            throw ApiException.conflict("REPORT_NOT_OPEN", "Every report on this has been resolved already");
        }
        switch (action) {
            case "HIDE_POST" -> postService.hide(target.id());
            case "DELETE_COMMENT" -> deleteComment(moderatorId, target);
            default -> {
                // DISMISS: the reports only.
            }
        }
        int resolved = reports.resolve(
                target, action.equals("DISMISS") ? "DISMISSED" : "ACTIONED", action, moderatorId, Instant.now(clock));
        return new Resolved(target.kind().name(), target.id(), action, resolved);
    }

    private static boolean fits(String action, Kind kind) {
        return switch (action) {
            case "HIDE_POST" -> kind == Kind.POST;
            case "DELETE_COMMENT" -> kind == Kind.COMMENT || kind == Kind.GIG_COMMENT;
            default -> true;
        };
    }

    /**
     * The admin delete the comment threads already have. A comment its author deleted meanwhile is left
     * as it is (the services answer 404 for it, and an exception here would roll the whole decision back).
     */
    private void deleteComment(UUID moderatorId, Target target) {
        Map<UUID, CommentRow> rows = target.kind() == Kind.COMMENT
                ? reports.comments(List.of(target.id()))
                : reports.gigComments(List.of(target.id()));
        CommentRow comment = rows.get(target.id());
        if (comment == null || comment.deleted()) {
            return;
        }
        if (target.kind() == Kind.COMMENT) {
            engagement.deleteComment(moderatorId, true, target.id());
        } else {
            gigComments.delete(moderatorId, true, target.id());
        }
    }
}
