package app.soundcheck.social.internal;

import static app.soundcheck.shared.jooq.social.Tables.ARTIST_FOLLOW;

import app.soundcheck.catalog.ArtistFollows;
import app.soundcheck.catalog.Catalog;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.social.ArtistFollowed;
import app.soundcheck.social.NotifyLevel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Artist follows ({@code social.artist_follow}). Both directions are idempotent; the
 * {@link ArtistFollowed} event is published only when a row was actually inserted. Also answers the
 * catalog's {@link ArtistFollows} questions, so catalog never touches social's table.
 */
@Service
@Transactional
public class ArtistFollowService implements ArtistFollows {

    private final DSLContext dsl;
    private final Catalog catalog;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ArtistFollowService(DSLContext dsl, Catalog catalog, ApplicationEventPublisher events, Clock clock) {
        this.dsl = dsl;
        this.catalog = catalog;
        this.events = events;
        this.clock = clock;
    }

    /** @return true when the follow is new */
    public boolean follow(UUID userId, UUID artistId) {
        if (!catalog.artistExists(artistId)) {
            throw ApiException.notFound("ARTIST_NOT_FOUND", "No artist " + artistId);
        }
        Instant now = Instant.now(clock);
        int inserted = dsl.insertInto(ARTIST_FOLLOW)
                .set(ARTIST_FOLLOW.USER_ID, userId)
                .set(ARTIST_FOLLOW.ARTIST_ID, artistId)
                .set(ARTIST_FOLLOW.CREATED_AT, now.atOffset(ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute();
        if (inserted == 1) {
            events.publishEvent(new ArtistFollowed(userId, artistId, now));
        }
        return inserted == 1;
    }

    public void unfollow(UUID userId, UUID artistId) {
        dsl.deleteFrom(ARTIST_FOLLOW)
                .where(ARTIST_FOLLOW.USER_ID.eq(userId))
                .and(ARTIST_FOLLOW.ARTIST_ID.eq(artistId))
                .execute();
    }

    /**
     * Sets what the follower hears about the artist. Only a follower has a level: 409
     * {@code NOT_FOLLOWING} otherwise.
     */
    public void setNotify(UUID userId, UUID artistId, NotifyLevel level) {
        int updated = dsl.update(ARTIST_FOLLOW)
                .set(ARTIST_FOLLOW.NOTIFY, level.name())
                .where(ARTIST_FOLLOW.USER_ID.eq(userId))
                .and(ARTIST_FOLLOW.ARTIST_ID.eq(artistId))
                .execute();
        if (updated == 0) {
            throw ApiException.conflict("NOT_FOLLOWING", "Follow this artist first");
        }
    }

    /** Expected index: the primary key (user_id, artist_id). */
    @Override
    @Transactional(readOnly = true)
    public Optional<String> notifyLevel(UUID userId, UUID artistId) {
        return dsl.select(ARTIST_FOLLOW.NOTIFY)
                .from(ARTIST_FOLLOW)
                .where(ARTIST_FOLLOW.USER_ID.eq(userId))
                .and(ARTIST_FOLLOW.ARTIST_ID.eq(artistId))
                .fetchOptional(ARTIST_FOLLOW.NOTIFY);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> followedBy(UUID userId, Collection<UUID> artistIds) {
        if (artistIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(dsl.select(ARTIST_FOLLOW.ARTIST_ID)
                .from(ARTIST_FOLLOW)
                .where(ARTIST_FOLLOW.USER_ID.eq(userId))
                .and(ARTIST_FOLLOW.ARTIST_ID.in(artistIds))
                .fetch(ARTIST_FOLLOW.ARTIST_ID));
    }

    /** Expected index: artist_follow_artist (artist_id). */
    @Override
    @Transactional(readOnly = true)
    public long followerCount(UUID artistId) {
        return dsl.fetchCount(ARTIST_FOLLOW, ARTIST_FOLLOW.ARTIST_ID.eq(artistId));
    }
}
