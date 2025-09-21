package app.soundcheck.catalog.internal;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.GeoPoint;
import app.soundcheck.catalog.VenueMergeParticipant;
import app.soundcheck.catalog.internal.VenueMergeRepository.SameNight;
import app.soundcheck.catalog.internal.VenueMergeRepository.VenueFacts;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merging a duplicate venue into the real one, the same shape as a gig merge:
 * in <b>one transaction</b>, catalog moves its own rows (gigs with their point, the sources' venue ids,
 * siblings, saved searches), then every {@link VenueMergeParticipant} (social's follows) moves its own. The
 * duplicate is deleted, {@code catalog.venue_redirect} keeps its id working and {@code catalog.venue_alias}
 * keeps its name: a fan or setlist.fm naming the old venue near the same place lands on the survivor.
 *
 * <p>Gigs that the move puts on the same night at one venue may be one show listed twice. They are listed
 * in the result for the operator to merge on the Merge tab; a venue merge never merges gigs by itself.
 */
@Service
@ConditionalOnRole(Roles.API)
public class VenueMerges {

    /** One kind of row and how many of them the venue has ("gigs" → 312). */
    @Schema(name = "VenueMergeCount")
    public record MoveCount(
            @Schema(description = "gigs, listings, siblings, savedSearches (catalog); followers (social)") String what,
            long count) {}

    /** One venue of the pair, as the operator compares them. */
    @Schema(name = "VenueMergeSide")
    public record Side(
            UUID id,
            String name,
            @Schema(nullable = true) String city,
            @Schema(nullable = true) String postcode,
            @Schema(nullable = true) GeoPoint location,
            @Schema(nullable = true, description = "The fan who added it; null for a source's venue") UUID addedBy,
            @Schema(description = "The sources' ids for it, as SOURCE:id") List<String> externalIds,
            List<MoveCount> counts) {}

    /** A gig of the duplicate and a gig of the survivor on the same UK day: maybe one show twice. */
    @Schema(name = "VenueMergeSameNight")
    public record SameNightPair(
            @Schema(description = "The duplicate's gig, which moves") UUID movedGigId,
            String movedTitle,
            @Schema(description = "The survivor's gig that night") UUID gigId,
            String title,
            LocalDate day) {}

    @Schema(name = "VenueMergePreview")
    public record Preview(
            Side from,
            Side into,
            @Schema(nullable = true, description = "How far apart the two points are; null when either has none")
                    Long distanceMetres,
            List<MoveCount> moving,
            @Schema(description = "Gigs that will share a night at the survivor; merge them on the Merge tab")
                    List<SameNightPair> sameNight,
            @Schema(description = "Why the merge would be refused, one sentence each; empty: it can go ahead")
                    List<String> conflicts) {}

    @Schema(name = "VenueMergeResult")
    public record Result(
            UUID into,
            List<MoveCount> moved,
            @Schema(description = "Gigs now on the same night at the survivor; not merged")
                    List<SameNightPair> sameNight) {}

    private final VenueMergeRepository repository;
    private final CatalogIngest ingest;
    private final List<VenueMergeParticipant> participants;
    private final Clock clock;

    public VenueMerges(
            VenueMergeRepository repository,
            CatalogIngest ingest,
            List<VenueMergeParticipant> participants,
            Clock clock) {
        this.repository = repository;
        this.ingest = ingest;
        this.participants = List.copyOf(participants);
        this.clock = clock;
    }

    /** Both venues side by side, what would move, the nights that would be shared. Changes nothing. */
    @Transactional(readOnly = true)
    public Preview preview(UUID from, UUID into) {
        refuseSame(from, into);
        VenueFacts f = live(from);
        VenueFacts i = live(into);
        return new Preview(
                side(f), side(i), distance(f, i), counts(from), sameNight(from, into), conflicts(from, into));
    }

    /**
     * Moves everything from {@code from} to {@code into}, then deletes {@code from} and leaves a redirect
     * and an alias.
     *
     * @throws ApiException 409 {@code VENUE_MERGE_SAME}, {@code VENUE_ALREADY_MERGED} or {@code
     *     MERGE_CONFLICT} (nothing moved), 404 {@code VENUE_NOT_FOUND}
     */
    @Transactional
    public Result merge(UUID from, UUID into, UUID adminId) {
        refuseSame(from, into);
        List<UUID> locked = repository.lock(from, into);
        requireLive(from, locked.contains(from));
        requireLive(into, locked.contains(into));
        List<String> conflicts = conflicts(from, into);
        if (!conflicts.isEmpty()) {
            throw ApiException.conflict("MERGE_CONFLICT", "The merge would break: " + String.join("; ", conflicts));
        }
        VenueFacts duplicate = live(from);
        List<MoveCount> moved = counts(from);
        List<SameNightPair> sameNight = sameNight(from, into);
        Instant now = clock.instant();

        repository.fillSurvivor(from, into, now);
        repository.moveGigs(from, into, now);
        repository.moveExternalIds(from, into);
        // Siblings join the survivor's group; linkSiblings keeps the group closed (every pair both ways).
        repository.takeSiblings(from, into).forEach(sibling -> ingest.linkSiblings(into, sibling));
        repository.moveSavedSearches(from, into);

        participants.forEach(p -> p.moveTo(from, into));

        repository.redirectAndAlias(from, into, duplicate.nameNorm(), adminId, now);
        repository.deleteVenue(from);
        return new Result(into, moved, sameNight);
    }

    private List<String> conflicts(UUID from, UUID into) {
        List<String> all = new ArrayList<>();
        participants.forEach(p -> all.addAll(p.conflicts(from, into)));
        return all;
    }

    /** Catalog's own rows first, then each participant's, in a fixed order. */
    private List<MoveCount> counts(UUID venueId) {
        List<MoveCount> counts = new ArrayList<>();
        repository.countsAt(venueId).forEach((what, n) -> counts.add(new MoveCount(what, n)));
        participants.forEach(p -> p.countsAt(venueId).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> counts.add(new MoveCount(e.getKey(), e.getValue()))));
        return counts;
    }

    private List<SameNightPair> sameNight(UUID from, UUID into) {
        return repository.sameNights(from, into).stream()
                .map((SameNight s) -> new SameNightPair(s.movedGigId(), s.movedTitle(), s.gigId(), s.title(), s.day()))
                .toList();
    }

    private Side side(VenueFacts v) {
        return new Side(
                v.id(),
                v.name(),
                v.city(),
                v.postcode(),
                v.lat() == null || v.lng() == null ? null : new GeoPoint(v.lat(), v.lng()),
                v.addedBy(),
                repository.externalIds(v.id()),
                counts(v.id()));
    }

    /** Great-circle distance, to the metre; enough to warn about two venues in different towns. */
    private static @Nullable Long distance(VenueFacts a, VenueFacts b) {
        if (a.lat() == null || a.lng() == null || b.lat() == null || b.lng() == null) {
            return null;
        }
        double p1 = Math.toRadians(a.lat());
        double p2 = Math.toRadians(b.lat());
        double dp = p2 - p1;
        double dl = Math.toRadians(b.lng() - a.lng());
        double h =
                Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return Math.round(2 * 6_371_000 * Math.asin(Math.sqrt(h)));
    }

    private VenueFacts live(UUID venueId) {
        return repository.facts(venueId).orElseThrow(() -> missing(venueId));
    }

    private void requireLive(UUID venueId, boolean exists) {
        if (!exists) {
            throw missing(venueId);
        }
    }

    /** 409 for an id that was merged away (its redirect says where), 404 for one that never existed. */
    private ApiException missing(UUID venueId) {
        @Nullable UUID survivor = repository.redirectOf(venueId).orElse(null);
        if (survivor != null) {
            return ApiException.conflict(
                    "VENUE_ALREADY_MERGED", "Venue " + venueId + " was already merged into " + survivor);
        }
        return ApiException.notFound("VENUE_NOT_FOUND", "No venue " + venueId);
    }

    private static void refuseSame(UUID from, UUID into) {
        if (from.equals(into)) {
            throw ApiException.conflict("VENUE_MERGE_SAME", "A venue can't be merged into itself");
        }
    }
}
