package app.soundcheck.content.internal;

import app.soundcheck.media.MediaUsage;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers media's {@link MediaUsage}: which live post, if any, an asset is a clip of. Media asks
 * when a fan opens an upload of a file they have uploaded before, to refuse a re-post of the same clip.
 */
@Component
@Transactional(readOnly = true)
class MediaUsageProvider implements MediaUsage {

    private final ClipRepository clips;

    MediaUsageProvider(ClipRepository clips) {
        this.clips = clips;
    }

    @Override
    public Optional<UUID> livePostOf(UUID mediaId) {
        return clips.livePostOf(mediaId);
    }
}
