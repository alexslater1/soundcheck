package app.soundcheck.catalog.internal;

import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ATTENDANCE;

import app.soundcheck.shared.web.Cursor;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** SQL for the console's queue of gigs fans added: the page, the hints, the review. */
@Repository
class FanGigRepository {

    /** A fan-added gig as the queue lists it. */
    record FanGigRow(UUID id, UUID addedBy, Instant addedAt, @Nullable Instant reviewedAt, @Nullable UUID reviewedBy) {}

    private final DSLContext dsl;

    FanGigRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Fan-added gigs, newest added first, reviewed or not. Expected index: gig_fan_added (created_at desc,
     * id desc) where created_by is not null (V148).
     */
    List<FanGigRow> page(boolean reviewed, @Nullable Cursor after, int limit) {
        Condition where =
                GIG.CREATED_BY.isNotNull().and(reviewed ? GIG.REVIEWED_AT.isNotNull() : GIG.REVIEWED_AT.isNull());
        if (after != null) {
            where = where.and(DSL.row(GIG.CREATED_AT, GIG.ID).lt(at(after.at()), after.id()));
        }
        return dsl.select(GIG.ID, GIG.CREATED_BY, GIG.CREATED_AT, GIG.REVIEWED_AT, GIG.REVIEWED_BY)
                .from(GIG)
                .where(where)
                .orderBy(GIG.CREATED_AT.desc(), GIG.ID.desc())
                .limit(limit)
                .fetch(r ->
                        new FanGigRow(r.value1(), r.value2(), r.value3().toInstant(), instant(r.value4()), r.value5()));
    }

    /** Marks per gig, every status. Expected index: gig_attendance_gig (gig_id, status). */
    Map<UUID, Long> attendanceCounts(Collection<UUID> gigIds) {
        Map<UUID, Long> counts = new LinkedHashMap<>();
        if (gigIds.isEmpty()) {
            return counts;
        }
        dsl.select(GIG_ATTENDANCE.GIG_ID, DSL.count())
                .from(GIG_ATTENDANCE)
                .where(GIG_ATTENDANCE.GIG_ID.in(gigIds))
                .groupBy(GIG_ATTENDANCE.GIG_ID)
                .forEach(r -> counts.put(r.value1(), r.value2().longValue()));
        return counts;
    }

    /**
     * Likely duplicates of each gig: another gig on the same UK day at the same venue, or sharing an act.
     * Catalog's own rough rule for a hint, <b>not</b> ingestion's {@code GigMatcher} (which compares titles
     * and bills, and lives in ingestion's internals where catalog can't call it). The operator decides.
     *
     * <p>Expected index: gig_starts_at (starts_at, id) for the other gig within a day either side; the UK
     * day test then runs on those few rows. gig_artist's primary key (gig first) for the shared act.
     */
    Map<UUID, List<UUID>> likelyDuplicates(Collection<UUID> gigIds, int perGig) {
        Map<UUID, List<UUID>> byGig = new LinkedHashMap<>();
        if (gigIds.isEmpty()) {
            return byGig;
        }
        var fan = GIG.as("fan");
        var other = GIG.as("other");
        var fanAct = GIG_ARTIST.as("fan_act");
        var otherAct = GIG_ARTIST.as("other_act");
        Field<Integer> rank = DSL.rowNumber()
                .over()
                .partitionBy(fan.ID)
                .orderBy(DSL.field(other.VENUE_ID.eq(fan.VENUE_ID)).desc(), other.STARTS_AT, other.ID);
        Field<UUID> fanId = fan.ID.as("fan_id");
        Field<UUID> otherId = other.ID.as("other_id");
        var pairs = dsl.select(fanId, otherId, rank.as("rank"))
                .from(fan)
                .join(other)
                .on(other.ID.ne(fan.ID))
                .and(other.STARTS_AT.between(shift(fan.STARTS_AT, "-1 day"), shift(fan.STARTS_AT, "1 day")))
                .and(ukDay(other.STARTS_AT).eq(ukDay(fan.STARTS_AT)))
                .and(other.VENUE_ID
                        .eq(fan.VENUE_ID)
                        .orExists(DSL.selectOne()
                                .from(fanAct)
                                .join(otherAct)
                                .on(otherAct.ARTIST_ID.eq(fanAct.ARTIST_ID))
                                .where(fanAct.GIG_ID.eq(fan.ID))
                                .and(otherAct.GIG_ID.eq(other.ID))))
                .where(fan.ID.in(gigIds))
                .asTable("pairs");
        Field<UUID> pairFan = pairs.field(fanId);
        Field<Integer> pairRank = pairs.field("rank", Integer.class);
        dsl.select(pairFan, pairs.field(otherId))
                .from(pairs)
                .where(pairRank.le(perGig))
                .orderBy(pairFan, pairRank)
                .forEach(r -> byGig.computeIfAbsent(r.value1(), k -> new ArrayList<>())
                        .add(r.value2()));
        return byGig;
    }

    /** True when the gig exists and a fan added it. Expected index: the gig primary key. */
    boolean fanAdded(UUID gigId) {
        return dsl.fetchExists(GIG, GIG.ID.eq(gigId).and(GIG.CREATED_BY.isNotNull()));
    }

    boolean exists(UUID gigId) {
        return dsl.fetchExists(GIG, GIG.ID.eq(gigId));
    }

    /** Marks it reviewed; a second review keeps the first one's time and operator. */
    void review(UUID gigId, UUID adminId, Instant now) {
        dsl.update(GIG)
                .set(GIG.REVIEWED_AT, at(now))
                .set(GIG.REVIEWED_BY, adminId)
                .where(GIG.ID.eq(gigId))
                .and(GIG.REVIEWED_AT.isNull())
                .execute();
    }

    /** Locks the gig for its delete; false when there is no such gig. */
    boolean lock(UUID gigId) {
        return dsl.select(GIG.ID)
                .from(GIG)
                .where(GIG.ID.eq(gigId))
                .forUpdate()
                .fetchOptional()
                .isPresent();
    }

    /** Deletes the gig; its marks, bill, listings and edits go with it (on delete cascade). */
    void delete(UUID gigId) {
        dsl.deleteFrom(GIG).where(GIG.ID.eq(gigId)).execute();
    }

    private static Field<OffsetDateTime> shift(Field<OffsetDateTime> at, String interval) {
        return DSL.field("{0} + {1}::interval", OffsetDateTime.class, at, DSL.inline(interval));
    }

    private static Field<LocalDate> ukDay(Field<OffsetDateTime> at) {
        return DSL.field("({0} at time zone 'Europe/London')::date", LocalDate.class, at);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime at) {
        return at == null ? null : at.toInstant();
    }
}
