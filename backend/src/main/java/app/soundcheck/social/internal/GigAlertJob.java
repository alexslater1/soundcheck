package app.soundcheck.social.internal;

import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the new-gig alert pass on one instance (ShedLock), in the scheduler role: every
 * {@code soundcheck.alerts.interval} (an hour by default; the local profile is quicker). Switched off in
 * tests ({@code soundcheck.alerts.enabled: false}), which run {@link GigAlertService#run()} themselves.
 */
@Component
@ConditionalOnRole(Roles.SCHEDULER)
class GigAlertJob {

    private final GigAlertService alerts;
    private final boolean enabled;

    GigAlertJob(GigAlertService alerts, @Value("${soundcheck.alerts.enabled:true}") boolean enabled) {
        this.alerts = alerts;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${soundcheck.alerts.interval:PT1H}", initialDelayString = "PT2M")
    @SchedulerLock(name = "new-gig-alerts", lockAtMostFor = "PT30M")
    void run() {
        if (enabled) {
            alerts.run();
        }
    }
}
