package app.soundcheck.media.internal;

import app.soundcheck.media.MediaInfo;
import app.soundcheck.media.MediaLibrary;
import app.soundcheck.media.ObjectStorage;
import app.soundcheck.media.Playback;
import app.soundcheck.shared.media.PublicImages;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Media's read API for other modules ({@link MediaLibrary}) and the image lookup declared in shared. */
@Service
@Transactional(readOnly = true)
class MediaLibraryService implements MediaLibrary, PublicImages {

    private final MediaAssetRepository assets;
    private final UploadService uploads;
    private final MediaProperties properties;
    private final ObjectStorage storage;
    private final Clock clock;

    MediaLibraryService(
            MediaAssetRepository assets,
            UploadService uploads,
            MediaProperties properties,
            ObjectStorage storage,
            Clock clock) {
        this.storage = storage;
        this.clock = clock;
        this.assets = assets;
        this.uploads = uploads;
        this.properties = properties;
    }

    @Override
    public Optional<MediaInfo> find(UUID mediaId) {
        return assets.find(mediaId).map(MediaLibraryService::info);
    }

    @Override
    public Map<UUID, MediaInfo> findAll(Collection<UUID> mediaIds) {
        Map<UUID, MediaInfo> byId = new LinkedHashMap<>();
        assets.findAll(mediaIds).forEach(asset -> byId.put(asset.id(), info(asset)));
        return byId;
    }

    @Override
    public Map<UUID, Playback> playbacks(Collection<UUID> mediaIds) {
        Map<UUID, Playback> byId = new LinkedHashMap<>();
        assets.findAll(mediaIds).forEach(asset -> byId.put(asset.id(), uploads.toPlayback(asset)));
        return byId;
    }

    /**
     * MANDATORY: a release only makes sense as part of the caller's delete, and must roll back with it.
     * Nothing touches storage here; {@link MediaObjectPurge} does that a week later, so a delete that is
     * undone by hand in the database within the week loses nothing.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int release(Collection<UUID> mediaIds) {
        return assets.release(mediaIds, Instant.now(clock));
    }

    /**
     * The thumbnail: avatars and a clip's blurred preview frame are shown small, so they need not load
     * the full photo (up to {@link ImageScrub#MAX_EDGE} pixels). Images stored before thumbnails existed
     * have their one copy as both.
     */
    @Override
    public Map<UUID, String> urls(Collection<UUID> mediaIds) {
        Map<UUID, String> byId = new LinkedHashMap<>();
        for (MediaAsset asset : assets.findAll(mediaIds)) {
            if (readyImage(asset)) {
                byId.put(asset.id(), properties.publicBaseUrl() + "/" + asset.thumbKey());
            }
        }
        return byId;
    }

    @Override
    public boolean readyImageOwnedBy(UUID mediaId, UUID ownerId) {
        return assets.find(mediaId)
                .filter(asset -> asset.ownerId().equals(ownerId) && readyImage(asset))
                .isPresent();
    }

    /**
     * Objects first, rows second: if storage fails, the rows are still there and the purge is tried again
     * the next day; the other way round would leave objects nobody knows about.
     */
    @Override
    @Transactional
    public int purgeOwnedBy(UUID ownerId) {
        for (UUID mediaId : assets.idsOwnedBy(ownerId)) {
            storage.deletePrefix("media/" + mediaId + "/");
        }
        storage.deletePrefix("uploads/" + ownerId + "/");
        return assets.deleteOwnedBy(ownerId);
    }

    private static boolean readyImage(MediaAsset asset) {
        return !asset.video() && asset.status().equals("READY") && asset.thumbKey() != null;
    }

    private static MediaInfo info(MediaAsset asset) {
        return new MediaInfo(asset.id(), asset.ownerId(), asset.kind(), asset.status());
    }
}
