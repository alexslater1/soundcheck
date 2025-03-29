package app.soundcheck.identity.internal;

import app.soundcheck.identity.AccountPurgeStep;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finishes account deletion: {@code DELETE /me} only marks the account deleted and
 * revokes its sessions; seven days later this removes everything. Each account is purged in its own
 * transaction: the modules' {@link AccountPurgeStep}s in order, then the user row itself, which cascades
 * to follows, blocks, attendance, likes, the inbox, genre choices and tokens. An account whose purge
 * fails is left as it was and tried again on the next run.
 */
@Service
public class AccountPurgeService {

    /** How long a deleted account is kept before it is purged. */
    public static final Duration GRACE = Duration.ofDays(7);

    private static final Logger log = LoggerFactory.getLogger(AccountPurgeService.class);

    static final int BATCH = 200;
    private static final int MAX_BATCHES = 50;

    private final UserRepository users;
    private final List<AccountPurgeStep> steps;
    private final TransactionTemplate tx;
    private final Clock clock;

    AccountPurgeService(UserRepository users, List<AccountPurgeStep> steps, TransactionTemplate tx, Clock clock) {
        this.users = users;
        this.steps = steps.stream()
                .sorted(Comparator.comparingInt(AccountPurgeStep::order))
                .toList();
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Purges every account deleted at least {@link #GRACE} ago.
     *
     * @return how many accounts were purged
     */
    public int purgeDue() {
        Instant deletedBefore = Instant.now(clock).minus(GRACE);
        int purged = 0;
        int failed = 0;
        // Purged accounts leave the work list; failed ones stay at its head, so each batch is read
        // after them. An account that cannot be purged therefore never holds up the ones behind it.
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<UUID> due = users.deletedBefore(deletedBefore, failed, BATCH);
            for (UUID userId : due) {
                try {
                    tx.executeWithoutResult(status -> {
                        steps.forEach(step -> step.purge(userId));
                        users.delete(userId);
                    });
                    purged++;
                } catch (RuntimeException e) {
                    // Left soft-deleted; the next run tries again. Never log more than the id.
                    failed++;
                    log.error("account purge failed for user {}: {}", userId, e.toString());
                }
            }
            if (due.size() < BATCH) {
                break;
            }
        }
        if (purged > 0) {
            log.info("purged {} deleted accounts", purged);
        }
        return purged;
    }
}
