package app.soundcheck.media;

import java.util.Optional;
import java.util.UUID;

/**
 * What media needs to know about posts to refuse the same file twice: whether an asset
 * is in a post that still exists. Media only declares this interface; the content module, which owns
 * posts and may depend on media, implements it (the same inversion as catalog's {@code GigClips}). Media
 * cannot depend on content, because content already depends on media.
 */
public interface MediaUsage {

    /**
     * The live post the asset is a clip of: PUBLISHED, PENDING_MEDIA, or
     * HIDDEN by a moderator (re-posting a hidden clip is exactly what the guard should stop). Empty when
     * the asset is in no post, or only in a FAILED or DELETED one.
     */
    Optional<UUID> livePostOf(UUID mediaId);
}
