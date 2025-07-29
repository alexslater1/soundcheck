package app.soundcheck.social.internal;

import static app.soundcheck.shared.jooq.social.Tables.ARTIST_FOLLOW;
import static app.soundcheck.shared.jooq.social.Tables.GIG_ALERT_SENT;
import static app.soundcheck.shared.jooq.social.Tables.GIG_ALERT_STATE;
import static app.soundcheck.shared.jooq.social.Tables.VENUE_FOLLOW;

import app.soundcheck.social.NotifyLevel;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** jOOQ access for the new-gig alert pass: its start, who follows what, and what was sent. */
@Repository
class GigAlertRepository {

    /** A gig to record as sent to one user. */
    record Sent(UUID gigId, Instant startsAt) {}

    private final DSLContext dsl;

    GigAlertRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    Optional<Instant> startedAt() {
        return dsl.select(GIG_ALERT_STATE.STARTED_AT).from(GIG_ALERT_STATE).fetchOptional(r -> r.value1()
                .toInstant());
    }

    /** Records when alerts started; a second call changes nothing. */
    void start(Instant at) {
        dsl.insertInto(GIG_ALERT_STATE)
                .set(GIG_ALERT_STATE.STARTED_AT, at(at))
                .onConflictDoNothing()
                .execute();
    }

    /** The follow levels that hear about new gigs ({@link NotifyLevel#gigs()}). */
    static final List<String> GIG_LEVELS = Arrays.stream(NotifyLevel.values())
            .filter(NotifyLevel::gigs)
            .map(NotifyLevel::name)
            .toList();

    /** Artist id → the users who follow it. Expected index: artist_follow_artist (artist_id). */
    Map<UUID, List<UUID>> artistFollowers(Collection<UUID> artistIds) {
        Map<UUID, List<UUID>> out = new HashMap<>();
        if (!artistIds.isEmpty()) {
            dsl.select(ARTIST_FOLLOW.ARTIST_ID, ARTIST_FOLLOW.USER_ID)
                    .from(ARTIST_FOLLOW)
                    .where(ARTIST_FOLLOW.ARTIST_ID.in(artistIds))
                    .and(ARTIST_FOLLOW.NOTIFY.in(GIG_LEVELS)) // a follow set to POSTS or NONE hears of no gigs
                    .forEach(r -> out.computeIfAbsent(r.value1(), k -> new ArrayList<>())
                            .add(r.value2()));
        }
        return out;
    }

    /** Venue id → the users who follow it. Expected index: venue_follow_venue (venue_id). */
    Map<UUID, List<UUID>> venueFollowers(Collection<UUID> venueIds) {
        Map<UUID, List<UUID>> out = new HashMap<>();
        if (!venueIds.isEmpty()) {
            dsl.select(VENUE_FOLLOW.VENUE_ID, VENUE_FOLLOW.USER_ID)
                    .from(VENUE_FOLLOW)
                    .where(VENUE_FOLLOW.VENUE_ID.in(venueIds))
                    .and(VENUE_FOLLOW.NOTIFY.in(GIG_LEVELS)) // a follow set to POSTS or NONE hears of no gigs
                    .forEach(r -> out.computeIfAbsent(r.value1(), k -> new ArrayList<>())
                            .add(r.value2()));
        }
        return out;
    }

    /**
     * Records the gigs as sent to the user and returns those that were not already: the only ones to
     * announce. Insert-or-nothing on the primary key, so a pass that is repeated after a failure, or two
     * passes at once, never announce a gig twice.
     */
    Set<UUID> markSent(UUID userId, List<Sent> gigs, Instant now) {
        if (gigs.isEmpty()) {
            return Set.of();
        }
        var insert = dsl.insertInto(
                GIG_ALERT_SENT,
                GIG_ALERT_SENT.USER_ID,
                GIG_ALERT_SENT.GIG_ID,
                GIG_ALERT_SENT.STARTS_AT,
                GIG_ALERT_SENT.SENT_AT);
        for (Sent gig : gigs) {
            insert = insert.values(userId, gig.gigId(), at(gig.startsAt()), at(now));
        }
        return new HashSet<>(
                insert.onConflictDoNothing().returning(GIG_ALERT_SENT.GIG_ID).fetch(GIG_ALERT_SENT.GIG_ID));
    }

    /** Forgets what was sent about gigs that started before {@code before}. Index: gig_alert_sent_starts. */
    int pruneSent(Instant before) {
        return dsl.deleteFrom(GIG_ALERT_SENT)
                .where(GIG_ALERT_SENT.STARTS_AT.lt(at(before)))
                .execute();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
