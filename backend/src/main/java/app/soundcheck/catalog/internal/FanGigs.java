package app.soundcheck.catalog.internal;

import app.soundcheck.catalog.GigClips;
import app.soundcheck.catalog.GigMergeParticipant;
import app.soundcheck.catalog.GigSummary;
import app.soundcheck.catalog.internal.FanGigRepository.FanGigRow;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Cursor;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The console's queue of gigs fans added: who added each one and when, how many marks
 * and posts it has, and the gigs it may duplicate. The operator marks it reviewed, merges it into the
 * real gig ({@link GigMerges}), or deletes it while nothing hangs off it.
 */
@Service
@ConditionalOnRole(Roles.API)
public class FanGigs {

    /** Likely duplicates listed per fan-added gig. */
    static final int HINTS_PER_GIG = 5;

    @Schema(name = "FanAddedGig")
    public record FanGig(
            GigSummary gig,
            @Schema(description = "The fan who added it; GET /admin/users/by-ids gives the handle") UUID addedBy,
            Instant addedAt,
            @Schema(nullable = true) Instant reviewedAt,
            @Schema(nullable = true) UUID reviewedBy,
            long attendance,
            @Schema(description = "Published posts (GigClips.countAt)") long posts,
            @Schema(
                            description =
                                    "Other gigs on the same UK day at the same venue or sharing an act: a hint from"
                                            + " catalog's own rule, not ingestion's matcher")
                    List<GigSummary> likelyDuplicates) {}

    private final FanGigRepository repository;
    private final CatalogService catalog;
    private final GigClips clips;
    private final List<GigMergeParticipant> participants;
    private final Clock clock;

    public FanGigs(
            FanGigRepository repository,
            CatalogService catalog,
            GigClips clips,
            List<GigMergeParticipant> participants,
            Clock clock) {
        this.repository = repository;
        this.catalog = catalog;
        this.clips = clips;
        this.participants = List.copyOf(participants);
        this.clock = clock;
    }

    /** Newest added first; {@code reviewed} picks the queue (false) or the reviewed archive (true). */
    @Transactional(readOnly = true)
    public Page<FanGig> page(UUID adminId, boolean reviewed, @Nullable String cursor, @Nullable Integer limit) {
        int size = Cursor.limit(limit);
        List<FanGigRow> rows = repository.page(reviewed, Cursor.decode(cursor), size + 1);
        boolean more = rows.size() > size;
        List<FanGigRow> page = more ? rows.subList(0, size) : rows;
        List<UUID> ids = page.stream().map(FanGigRow::id).toList();

        Map<UUID, List<UUID>> hints = repository.likelyDuplicates(ids, HINTS_PER_GIG);
        Set<UUID> wanted = new LinkedHashSet<>(ids);
        hints.values().forEach(wanted::addAll);
        Map<UUID, GigSummary> gigs = catalog.gigsByIds(adminId, wanted);
        Map<UUID, Long> marks = repository.attendanceCounts(ids);

        List<FanGig> items = new ArrayList<>();
        for (FanGigRow row : page) {
            GigSummary gig = gigs.get(row.id());
            if (gig == null) {
                continue; // deleted between the two reads
            }
            List<GigSummary> duplicates = hints.getOrDefault(row.id(), List.of()).stream()
                    .map(gigs::get)
                    .filter(g -> g != null)
                    .toList();
            items.add(new FanGig(
                    gig,
                    row.addedBy(),
                    row.addedAt(),
                    row.reviewedAt(),
                    row.reviewedBy(),
                    marks.getOrDefault(row.id(), 0L),
                    clips.countAt(row.id()),
                    duplicates));
        }
        String next = more ? new Cursor(page.getLast().addedAt(), page.getLast().id()).encode() : null;
        return new Page<>(items, next);
    }

    /** @throws ApiException 404 {@code GIG_NOT_FOUND}, 409 {@code GIG_NOT_FAN_ADDED} */
    @Transactional
    public void review(UUID gigId, UUID adminId) {
        requireFanAdded(gigId);
        repository.review(gigId, adminId, clock.instant());
    }

    /**
     * Deletes a gig nothing of value hangs off. Posts block it ({@code content.post} has no cascade), and
     * so do gig comments, which the cascade would silently lose: merge such a gig instead. Fans' marks,
     * the bill and the listings go with it.
     *
     * @throws ApiException 404 {@code GIG_NOT_FOUND}, 409 {@code GIG_HAS_POSTS} or {@code GIG_HAS_COMMENTS}
     */
    @Transactional
    public void delete(UUID gigId) {
        if (!repository.lock(gigId)) {
            throw ApiException.notFound("GIG_NOT_FOUND", "No gig " + gigId);
        }
        long posts = 0;
        long comments = 0;
        for (GigMergeParticipant p : participants) {
            Map<String, Long> counts = p.countsAt(gigId);
            posts += counts.getOrDefault(GigMergeParticipant.POSTS, 0L);
            comments += counts.getOrDefault(GigMergeParticipant.GIG_COMMENTS, 0L);
        }
        if (posts > 0) {
            throw ApiException.conflict(
                    "GIG_HAS_POSTS", "The gig has " + posts + " post(s): merge it into the real gig instead");
        }
        if (comments > 0) {
            throw ApiException.conflict(
                    "GIG_HAS_COMMENTS", "The gig has " + comments + " comment(s): merge it into the real gig instead");
        }
        repository.delete(gigId);
    }

    private void requireFanAdded(UUID gigId) {
        if (!repository.fanAdded(gigId)) {
            if (!repository.exists(gigId)) {
                throw ApiException.notFound("GIG_NOT_FOUND", "No gig " + gigId);
            }
            throw ApiException.conflict("GIG_NOT_FAN_ADDED", "Gig " + gigId + " came from a source, not a fan");
        }
    }
}
