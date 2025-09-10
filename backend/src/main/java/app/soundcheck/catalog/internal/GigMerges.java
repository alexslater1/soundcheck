package app.soundcheck.catalog.internal;

import app.soundcheck.catalog.GigMergeParticipant;
import app.soundcheck.catalog.GigSummary;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merging a duplicate gig into the real one. Everything that points at the
 * duplicate moves to the survivor in <b>one transaction</b>: catalog's own rows here, then every {@link
 * GigMergeParticipant} (content, social, ingestion). The duplicate is then deleted and {@code
 * catalog.gig_redirect} keeps its id working. A conflict any participant reports refuses the whole merge
 * before anything has moved. Replaces {@code ops/scripts/merge-duplicate-gig.sql}.
 */
@Service
@ConditionalOnRole(Roles.API)
public class GigMerges {

    static final ZoneId UK = ZoneId.of("Europe/London");

    /** One kind of row and how many of them the duplicate has ("posts" → 3). */
    @Schema(name = "GigMergeCount")
    public record MoveCount(
            @Schema(
                            description = "attendance, acts, listings, edits (catalog); posts, gigComments (content);"
                                    + " alertsSent (social); matchReviews (ingestion)")
                    String what,
            long count) {}

    /** One listing of a gig ({@code catalog.gig_source}); the primary one may rewrite the gig (V007). */
    @Schema(name = "GigMergeListing")
    public record Listing(String source, String externalId, @Schema(nullable = true) String url, boolean primary) {}

    /** One gig of the pair, as the operator compares them. */
    @Schema(name = "GigMergeSide")
    public record Side(
            GigSummary gig,
            List<Listing> listings,
            @Schema(nullable = true, description = "The fan who added it; null for a source's listing") UUID addedBy,
            List<MoveCount> counts) {}

    @Schema(name = "GigMergePreview")
    public record Preview(
            Side from,
            Side into,
            @Schema(description = "Both start on the same UK calendar day") boolean sameDay,
            boolean sameVenue,
            @Schema(description = "What moves to the survivor: the duplicate's rows, by kind") List<MoveCount> moving,
            @Schema(description = "Why the merge would be refused, one sentence each; empty: it can go ahead")
                    List<String> conflicts) {}

    @Schema(name = "GigMergeResult")
    public record Result(UUID into, List<MoveCount> moved) {}

    private final GigMergeRepository repository;
    private final SubmissionRepository submissions;
    private final CatalogReadRepository reads;
    private final CatalogService catalog;
    private final List<GigMergeParticipant> participants;
    private final Clock clock;

    public GigMerges(
            GigMergeRepository repository,
            SubmissionRepository submissions,
            CatalogReadRepository reads,
            CatalogService catalog,
            List<GigMergeParticipant> participants,
            Clock clock) {
        this.repository = repository;
        this.submissions = submissions;
        this.reads = reads;
        this.catalog = catalog;
        this.participants = List.copyOf(participants);
        this.clock = clock;
    }

    /** Both gigs side by side, what would move, and what stands in the way. Changes nothing. */
    @Transactional(readOnly = true)
    public Preview preview(UUID from, UUID into, UUID adminId) {
        refuseSameGig(from, into);
        requireLive(from, reads.gigExists(from));
        requireLive(into, reads.gigExists(into));
        Map<UUID, GigSummary> gigs = catalog.gigsByIds(adminId, List.of(from, into));
        Side fromSide = side(gigs.get(from));
        Side intoSide = side(gigs.get(into));
        return new Preview(
                fromSide,
                intoSide,
                ukDay(fromSide.gig().startsAt()).equals(ukDay(intoSide.gig().startsAt())),
                fromSide.gig().venue().id().equals(intoSide.gig().venue().id()),
                fromSide.counts(),
                conflicts(from, into));
    }

    /**
     * Moves everything from {@code from} to {@code into}, then deletes {@code from} and leaves a redirect.
     *
     * @throws ApiException 409 {@code MERGE_SAME_GIG}, {@code GIG_ALREADY_MERGED} or {@code MERGE_CONFLICT}
     *     (nothing moved), 404 {@code GIG_NOT_FOUND}
     */
    @Transactional
    public Result merge(UUID from, UUID into, UUID adminId) {
        refuseSameGig(from, into);
        List<UUID> locked = repository.lock(from, into);
        requireLive(from, locked.contains(from));
        requireLive(into, locked.contains(into));
        List<String> conflicts = conflicts(from, into);
        if (!conflicts.isEmpty()) {
            throw ApiException.conflict("MERGE_CONFLICT", "The merge would break: " + String.join("; ", conflicts));
        }
        List<MoveCount> moved = counts(from);
        Instant now = clock.instant();

        repository.mergeAttendance(from, into, now);
        repository.moveMissedAndDropAttendance(from, into);
        repository.mergeActs(from, into);
        repository.moveSources(from, into);
        repository.moveEdits(from, into);
        submissions.logEdit(into, adminId, "MERGE", from.toString(), into.toString(), now);

        participants.forEach(p -> p.moveTo(from, into));

        repository.redirect(from, into, adminId, now);
        repository.deleteGig(from);
        return new Result(into, moved);
    }

    private List<String> conflicts(UUID from, UUID into) {
        List<String> all = new ArrayList<>();
        participants.forEach(p -> all.addAll(p.conflicts(from, into)));
        return all;
    }

    /** Catalog's own rows first, then each participant's, in a fixed order. */
    private List<MoveCount> counts(UUID gigId) {
        List<MoveCount> counts = new ArrayList<>();
        repository.countsAt(gigId).forEach((what, n) -> counts.add(new MoveCount(what, n)));
        participants.forEach(p -> p.countsAt(gigId).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> counts.add(new MoveCount(e.getKey(), e.getValue()))));
        return counts;
    }

    private Side side(GigSummary gig) {
        return new Side(
                gig,
                repository.listings(gig.id()),
                submissions.gigAddedBy(gig.id()).orElse(null),
                counts(gig.id()));
    }

    private static void refuseSameGig(UUID from, UUID into) {
        if (from.equals(into)) {
            throw ApiException.conflict("MERGE_SAME_GIG", "A gig can't be merged into itself");
        }
    }

    /** 409 for an id that was merged away (its redirect says where), 404 for one that never existed. */
    private void requireLive(UUID gigId, boolean exists) {
        if (exists) {
            return;
        }
        @Nullable UUID survivor = reads.redirectOf(gigId).orElse(null);
        if (survivor != null) {
            throw ApiException.conflict("GIG_ALREADY_MERGED", "Gig " + gigId + " was already merged into " + survivor);
        }
        throw ApiException.notFound("GIG_NOT_FOUND", "No gig " + gigId);
    }

    private static java.time.LocalDate ukDay(Instant at) {
        return at.atZone(UK).toLocalDate();
    }
}
