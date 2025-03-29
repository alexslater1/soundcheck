package app.soundcheck.identity.internal;

import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the account purge once a day, on one instance (ShedLock), in the scheduler role. */
@Component
@ConditionalOnRole(Roles.SCHEDULER)
class AccountPurgeJob {

    private final AccountPurgeService purge;

    AccountPurgeJob(AccountPurgeService purge) {
        this.purge = purge;
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "Europe/London")
    @SchedulerLock(name = "account-purge", lockAtMostFor = "PT30M")
    void run() {
        purge.purgeDue();
    }
}
