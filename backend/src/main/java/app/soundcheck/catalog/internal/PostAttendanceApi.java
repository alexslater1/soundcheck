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
    private final SubmissionRepository submissions;
    private final Clock clock;

    PostAttendanceApi(CatalogReadRepository repository, SubmissionRepository submissions, Clock clock) {
        this.repository = repository;
        this.submissions = submissions;
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
                    // An act filmed that is not on the bill joins it as the filmer's fan-added act, logged
                    // as an edit; being on the bill, it counts as seen.
                    for (UUID act : actsFilmed) {
                        if (submissions.addAct(gigId, act, userId)) {
                            submissions.logEdit(gigId, userId, "ACT_ADDED", null, act.toString(), now);
                        }
                    }
                    repository.unmiss(userId, gigId, actsFilmed);
                });
    }

    @Override
    public void lastPostGone(UUID userId, UUID gigId) {
        repository.clearWentFromPost(userId, gigId);
    }
}
