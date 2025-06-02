package app.soundcheck.ingestion.internal.pastgigs;

import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.shared.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The day's allowance of setlist.fm calls (the free key allows 1,440 a day), shared by every
 * lookup of this instance: past gigs while adding a gig, and setlists on gig pages.
 */
@Component
public class SetlistFmBudget {

    private final int daily;
    private LocalDate day;
    private int spent;

    public SetlistFmBudget(IngestionProperties properties, Clock clock) {
        this.daily = properties.setlistfm().dailyBudget();
        this.day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    /** Counts one call against today's budget (UTC day), refusing (503 PAST_GIGS_BUSY) once it is spent. */
    public synchronized void spend(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (!today.equals(day)) {
            day = today;
            spent = 0;
        }
        if (spent >= daily) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "PAST_GIGS_BUSY", "setlist.fm lookups are used up for today");
        }
        spent++;
    }
}
