package app.soundcheck.social.internal;

import static app.soundcheck.shared.jooq.social.Tables.VENUE_FOLLOW;

import app.soundcheck.catalog.Catalog;
import app.soundcheck.catalog.VenueFollows;
import app.soundcheck.shared.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Venue follows: like artist follows, both directions idempotent. A followed
 * venue's new gigs reach the follower as an alert ({@link GigAlertService}). Also answers the catalog's
 * {@link VenueFollows} questions for the venue page, so catalog never touches social's table.
 */
@Service
@Transactional
public class VenueFollowService implements VenueFollows {

    private final DSLContext dsl;
    private final Catalog catalog;
    private final Clock clock;

    public VenueFollowService(DSLContext dsl, Catalog catalog, Clock clock) {
        this.dsl = dsl;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** @return true when the follow is new */
    public boolean follow(UUID userId, UUID venueId) {
        if (!catalog.venueExists(venueId)) {
            throw ApiException.notFound("VENUE_NOT_FOUND", "No venue " + venueId);
        }
        return dsl.insertInto(VENUE_FOLLOW)
                        .set(VENUE_FOLLOW.USER_ID, userId)
                        .set(VENUE_FOLLOW.VENUE_ID, venueId)
                        .set(VENUE_FOLLOW.CREATED_AT, Instant.now(clock).atOffset(ZoneOffset.UTC))
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    public void unfollow(UUID userId, UUID venueId) {
        dsl.deleteFrom(VENUE_FOLLOW)
                .where(VENUE_FOLLOW.USER_ID.eq(userId))
                .and(VENUE_FOLLOW.VENUE_ID.eq(venueId))
                .execute();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> followedBy(UUID userId, Collection<UUID> venueIds) {
        if (venueIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(dsl.select(VENUE_FOLLOW.VENUE_ID)
                .from(VENUE_FOLLOW)
                .where(VENUE_FOLLOW.USER_ID.eq(userId))
                .and(VENUE_FOLLOW.VENUE_ID.in(venueIds))
                .fetch(VENUE_FOLLOW.VENUE_ID));
    }

    /** Expected index: venue_follow_venue (venue_id). */
    @Override
    @Transactional(readOnly = true)
    public long followerCount(UUID venueId) {
        return dsl.fetchCount(VENUE_FOLLOW, VENUE_FOLLOW.VENUE_ID.eq(venueId));
    }
}
