package app.soundcheck.media.internal;

import app.soundcheck.media.ObjectStorage;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The second half of a clip's end of life. Once a
 * day, on one scheduler instance, it removes from storage the files of every asset that has been DELETED
 * for more than {@link #RETENTION}: a deleted post's clips and preview frames ({@code MediaLibrary.release})
 * and aborted uploads. The original, the renditions and the poster go (the same key layout as
 * {@code purgeOwnedBy}); the row stays, with {@code objects_purged_at} set, because content's
 * {@code post_clip} still references it.
 *
 * <p>Each asset is done on its own: files first, then the row's mark. If storage fails, the asset is still
 * unmarked and the next run tries it again; deleting a prefix that is already empty is harmless.
 */
@Component
@ConditionalOnRole(Roles.SCHEDULER)
public class MediaObjectPurge {

    private static final Logger log = LoggerFactory.getLogger(MediaObjectPurge.class);

    /** How long a released asset's files are kept: a week to undo a mistaken delete by hand. */
    public static final Duration RETENTION = Duration.ofDays(7);

    static final int BATCH = 100;

    private final MediaAssetRepository assets;
    private final ObjectStorage storage;
    private final Clock clock;

    MediaObjectPurge(MediaAssetRepository assets, ObjectStorage storage, Clock clock) {
        this.assets = assets;
        this.storage = storage;
        this.clock = clock;
    }

    @Scheduled(cron = "${soundcheck.media.purge-cron:0 50 3 * * *}", zone = "Europe/London")
    @SchedulerLock(name = "media-object-purge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void run() {
        int purged = purgeDue();
        if (purged > 0) {
            log.info("Removed the files of {} media assets deleted more than {} days ago", purged, RETENTION.toDays());
        }
    }

    /**
     * Purges everything due, in batches of {@link #BATCH}, until a batch is short or none of it could be
     * purged (storage is failing: stop, and try again tomorrow).
     *
     * @return how many assets had their files removed
     */
    public int purgeDue() {
        Instant cutoff = Instant.now(clock).minus(RETENTION);
        int purged = 0;
        for (; ; ) {
            List<MediaAssetRepository.Purgeable> batch = assets.purgeable(cutoff, BATCH);
            int done = 0;
            for (MediaAssetRepository.Purgeable asset : batch) {
                if (purge(asset)) {
                    done++;
                }
            }
            purged += done;
            if (batch.size() < BATCH || done == 0) {
                return purged;
            }
        }
    }

    private boolean purge(MediaAssetRepository.Purgeable asset) {
        try {
            storage.deletePrefix("media/" + asset.id() + "/");
            storage.deletePrefix("uploads/" + asset.ownerId() + "/" + asset.id() + "/");
            assets.markObjectsPurged(asset.id(), Instant.now(clock));
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not remove the files of media {}; will try again tomorrow", asset.id(), e);
            return false;
        }
    }
}
