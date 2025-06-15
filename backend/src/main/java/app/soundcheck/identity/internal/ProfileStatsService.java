package app.soundcheck.identity.internal;

import app.soundcheck.catalog.AttendanceStats;
import app.soundcheck.catalog.GigStats;
import app.soundcheck.catalog.GigSummary;
import app.soundcheck.identity.ProfileFacts;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Page;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stats section of a profile. Identity owns the profile and the owner's choice to share
 * it, so it decides who may look; the catalog, which owns attendance, does the counting.
 */
@Service
@Transactional(readOnly = true)
public class ProfileStatsService {

    private final UserRepository users;
    private final ProfileFacts facts;
    private final AttendanceStats stats;

    public ProfileStatsService(UserRepository users, ProfileFacts facts, AttendanceStats stats) {
        this.users = users;
        this.facts = facts;
        this.stats = stats;
    }

    public GigStats stats(UUID viewerId, UUID userId, int top) {
        requireVisible(viewerId, userId);
        return stats.of(userId, top);
    }

    public Page<GigSummary> gigs(
            UUID viewerId,
            UUID userId,
            @Nullable UUID artistId,
            @Nullable UUID venueId,
            @Nullable String cursor,
            @Nullable Integer limit) {
        requireVisible(viewerId, userId);
        return stats.wentGigs(viewerId, userId, artistId, venueId, cursor, limit);
    }

    /**
     * 404 when there is no such live user or they blocked the viewer (as for the profile itself);
     * 403 {@code STATS_HIDDEN} when the profile is there but its stats are not shared with the viewer.
     */
    private void requireVisible(UUID viewerId, UUID userId) {
        UserRecord owner = users.findById(userId).filter(u -> !u.deleted()).orElse(null);
        ProfileFacts.Relation relation = facts.relation(viewerId, userId);
        if (owner == null || relation.blockedBy()) {
            throw ApiException.notFound("USER_NOT_FOUND", "No user " + userId);
        }
        if (!MeService.statsVisible(viewerId, userId, owner.showGigStats(), relation)) {
            throw ApiException.forbidden("STATS_HIDDEN", "This user does not share their gig stats");
        }
    }
}
