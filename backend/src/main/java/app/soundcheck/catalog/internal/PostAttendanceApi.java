package app.soundcheck.catalog.internal;

import app.soundcheck.catalog.PostAttendance;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link PostAttendance} over {@code catalog.gig_attendance}. Joins the caller's transaction. */
@Component
@Transactional
class PostAttendanceApi implements PostAttendance {

    private final CatalogReadRepository repository;
    private final Clock clock;

    PostAttendanceApi(CatalogReadRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void postWentLive(UUID userId, UUID gigId, Collection<UUID> actsFilmed) {
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, UserSubmissionService.UK);
        repository
                .startsAt(gigId)
                .filter(startsAt ->
                        !LocalDate.ofInstant(startsAt, UserSubmissionService.UK).isAfter(today))
                .ifPresent(startsAt -> {
                    repository.markWentFromPost(userId, gigId, now);
                    repository.unmiss(userId, gigId, actsFilmed);
                });
    }

    @Override
    public void lastPostGone(UUID userId, UUID gigId) {
        repository.clearWentFromPost(userId, gigId);
    }
}
