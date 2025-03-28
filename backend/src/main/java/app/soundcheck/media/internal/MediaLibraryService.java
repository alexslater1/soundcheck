package app.soundcheck.media.internal;

import app.soundcheck.media.MediaInfo;
import app.soundcheck.media.MediaLibrary;
import app.soundcheck.media.Playback;
import app.soundcheck.shared.media.PublicImages;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Media's read API for other modules ({@link MediaLibrary}) and the image lookup declared in shared. */
@Service
@Transactional(readOnly = true)
class MediaLibraryService implements MediaLibrary, PublicImages {

    private final MediaAssetRepository assets;
    private final UploadService uploads;
    private final MediaProperties properties;

    MediaLibraryService(MediaAssetRepository assets, UploadService uploads, MediaProperties properties) {
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

    @Override
    public Map<UUID, String> urls(Collection<UUID> mediaIds) {
        Map<UUID, String> byId = new LinkedHashMap<>();
        for (MediaAsset asset : assets.findAll(mediaIds)) {
            if (readyImage(asset)) {
                byId.put(asset.id(), properties.publicBaseUrl() + "/" + asset.posterKey());
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

    private static boolean readyImage(MediaAsset asset) {
        return !asset.video() && asset.status().equals("READY") && asset.posterKey() != null;
    }

    private static MediaInfo info(MediaAsset asset) {
        return new MediaInfo(asset.id(), asset.ownerId(), asset.kind(), asset.status());
    }
}
