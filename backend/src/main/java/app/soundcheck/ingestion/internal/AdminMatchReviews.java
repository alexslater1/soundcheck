package app.soundcheck.ingestion.internal;

import static app.soundcheck.shared.jooq.ingestion.Tables.MATCH_REVIEW;

import app.soundcheck.catalog.CatalogViews;
import app.soundcheck.catalog.GigMerging;
import app.soundcheck.catalog.GigSummary;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.jooq.ingestion.tables.records.MatchReviewRecord;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Cursor;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The console's match reviews: listings the resolver almost matched to an existing gig and kept
 * apart ({@code ingestion.match_review}, V031), for the operator to decide.
 *
 * <ul>
 *   <li><b>MERGE</b>: the listing's gig is merged into the existing one through catalog's {@link
 *       GigMerging}, in this transaction, or the other way round when the operator keeps the
 *       new one ({@link Keep#NEW}: its listing may have the better title or times). A conflict refuses the
 *       merge and the review stays OPEN.
 *   <li><b>KEEP_APART</b>: the two stay two gigs; only the review changes.
 * </ul>
 *
 * Neither decision needs remembering for the resolver: once a listing is linked through {@code
 * catalog.gig_source}, later runs find its gig by the listing and never match it again.
 *
 * <p>A review one of whose gigs has since gone (a fan-added gig the operator deleted, a past gig the
 * setlist.fm purge removed) asks about nothing: the queue leaves it out.
 */
@Service
@ConditionalOnRole(Roles.API)
public class AdminMatchReviews {

    static final Set<String> STATUSES = Set.of("OPEN", "MERGED", "KEPT_APART");

    /** One review: the listing's gig, the gig it almost was, and why the resolver hesitated. */
    @Schema(name = "MatchReview")
    public record ReviewView(
            UUID id,
            Instant createdAt,
            @Schema(description = "The incoming listing's source and id") String source,
            String externalId,
            @Schema(allowableValues = {"OPEN", "MERGED", "KEPT_APART"}) String status,
            @Schema(nullable = true) Instant decidedAt,
            @Schema(description = "The gig the listing became") GigSummary gig,
            @Schema(description = "The existing gig it almost matched (a MERGE keeps this one)") GigSummary candidate,
            @Schema(description = "Titles as the resolver compared them") String title,
            String candidateTitle,
            double titleSimilarity,
            double actSimilarity,
            String reason) {}

    public enum Decision {
        MERGE,
        KEEP_APART
    }

    /** Which gig a MERGE keeps: the one that was there first (the default), or the listing's new one. */
    public enum Keep {
        EXISTING,
        NEW
    }

    private final DSLContext dsl;
    private final CatalogViews catalog;
    private final GigMerging merging;
    private final Clock clock;

    public AdminMatchReviews(DSLContext dsl, CatalogViews catalog, GigMerging merging, Clock clock) {
        this.dsl = dsl;
        this.catalog = catalog;
        this.merging = merging;
        this.clock = clock;
    }

    /**
     * Newest first, keyset on (created_at, id). Expected index: match_review_open (partial, OPEN) for the
     * queue; the decided ones scan a small table. A review whose gig or candidate no longer exists is left
     * out, so a page can be shorter than asked while a next cursor still follows.
     */
    @Transactional(readOnly = true)
    public Page<ReviewView> list(UUID adminId, String status, @Nullable String cursor, @Nullable Integer limit) {
        if (!STATUSES.contains(status)) {
            throw ApiException.badRequest("STATUS_INVALID", "status is OPEN, MERGED or KEPT_APART");
        }
        Cursor after = Cursor.decode(cursor);
        int size = Cursor.limit(limit);
        Condition where = MATCH_REVIEW.STATUS.eq(status);
        if (after != null) {
            where = where.and(DSL.row(MATCH_REVIEW.CREATED_AT, MATCH_REVIEW.ID)
                    .lt(DSL.row(after.at().atOffset(ZoneOffset.UTC), after.id())));
        }
        List<MatchReviewRecord> rows = dsl.selectFrom(MATCH_REVIEW)
                .where(where)
                .orderBy(MATCH_REVIEW.CREATED_AT.desc(), MATCH_REVIEW.ID.desc())
                .limit(size + 1)
                .fetch();
        boolean more = rows.size() > size;
        List<MatchReviewRecord> page = more ? rows.subList(0, size) : rows;
        Map<UUID, GigSummary> gigs = catalog.gigs(
                adminId,
                page.stream()
                        .flatMap(r -> Stream.of(r.getGigId(), r.getCandidateGigId()))
                        .distinct()
                        .toList());
        List<ReviewView> items = new ArrayList<>();
        for (MatchReviewRecord r : page) {
            GigSummary gig = gigs.get(r.getGigId());
            GigSummary candidate = gigs.get(r.getCandidateGigId());
            if (gig != null && candidate != null) {
                items.add(view(r, gig, candidate));
            }
        }
        if (!more) {
            return Page.of(items);
        }
        MatchReviewRecord last = page.getLast();
        return new Page<>(items, new Cursor(last.getCreatedAt().toInstant(), last.getId()).encode());
    }

    /**
     * Decides an OPEN review.
     *
     * @throws ApiException 404 {@code REVIEW_NOT_FOUND}; 409 {@code REVIEW_DECIDED}; the merge's own 409s
     *     ({@code MERGE_CONFLICT}, {@code GIG_ALREADY_MERGED}) and 404 {@code GIG_NOT_FOUND}, after which
     *     the review is still OPEN
     */
    @Transactional
    public void decide(UUID reviewId, Decision decision, Keep keep, UUID adminId) {
        MatchReviewRecord review = dsl.selectFrom(MATCH_REVIEW)
                .where(MATCH_REVIEW.ID.eq(reviewId))
                .forUpdate()
                .fetchOptional()
                .orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND", "No match review " + reviewId));
        if (!"OPEN".equals(review.getStatus())) {
            throw ApiException.conflict("REVIEW_DECIDED", "This review was already decided: " + review.getStatus());
        }
        if (decision == Decision.MERGE) {
            // By default the new listing's gig goes into the gig that was there first. Either way the
            // merge's ingestion participant re-points this review to the survivor and marks it MERGED;
            // the update below says so plainly.
            if (keep == Keep.NEW) {
                merging.mergeDuplicate(review.getCandidateGigId(), review.getGigId(), adminId);
            } else {
                merging.mergeDuplicate(review.getGigId(), review.getCandidateGigId(), adminId);
            }
        }
        dsl.update(MATCH_REVIEW)
                .set(MATCH_REVIEW.STATUS, decision == Decision.MERGE ? "MERGED" : "KEPT_APART")
                .set(MATCH_REVIEW.DECIDED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .where(MATCH_REVIEW.ID.eq(reviewId))
                .execute();
    }

    private static ReviewView view(MatchReviewRecord r, GigSummary gig, GigSummary candidate) {
        return new ReviewView(
                r.getId(),
                r.getCreatedAt().toInstant(),
                r.getSource(),
                r.getExternalId(),
                r.getStatus(),
                r.getDecidedAt() == null ? null : r.getDecidedAt().toInstant(),
                gig,
                candidate,
                r.getTitle(),
                r.getCandidateTitle(),
                r.getTitleSimilarity(),
                r.getActSimilarity(),
                r.getReason());
    }
}
