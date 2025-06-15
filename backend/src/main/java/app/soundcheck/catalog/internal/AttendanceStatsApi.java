package app.soundcheck.catalog.internal;

import app.soundcheck.catalog.AttendanceStats;
import app.soundcheck.catalog.GigStats;
import app.soundcheck.catalog.GigSummary;
import app.soundcheck.shared.web.Page;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link AttendanceStats}: three counting queries for the stats, the gig list from {@link CatalogService}. */
@Component
@Transactional(readOnly = true)
class AttendanceStatsApi implements AttendanceStats {

    static final ZoneId UK = ZoneId.of("Europe/London");

    private final AttendanceStatsRepository repository;
    private final CatalogService catalog;
    private final Clock clock;

    AttendanceStatsApi(AttendanceStatsRepository repository, CatalogService catalog, Clock clock) {
        this.repository = repository;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Override
    public GigStats of(UUID userId, int top) {
        if (top < 1 || top > MAX_TOP) {
            throw new IllegalArgumentException("top must be between 1 and " + MAX_TOP + ": " + top);
        }
        Instant now = clock.instant();
        AttendanceStatsRepository.Counts counts = repository.counts(userId, now);
        return new GigStats(
                counts.totals(),
                LocalDate.ofInstant(now, UK).getYear(),
                counts.years(),
                repository.topActs(userId, now, top),
                repository.topVenues(userId, now, top));
    }

    @Override
    public Page<GigSummary> wentGigs(
            UUID viewerId,
            UUID userId,
            @Nullable UUID artistId,
            @Nullable UUID venueId,
            @Nullable String cursor,
            @Nullable Integer limit) {
        return catalog.wentGigs(viewerId, userId, artistId, venueId, cursor, limit);
    }
}
