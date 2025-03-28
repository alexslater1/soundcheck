package app.soundcheck.shared.media;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * "Where is this image served from?", asked by modules that may not depend on the media module
 * (identity stores an avatar's media id and needs its URL). Declared here, in the open shared module,
 * and implemented by media: the compile-time dependency points media → shared, never identity → media.
 */
public interface PublicImages {

    /** Public URLs of the READY images among the given ids; anything else is simply absent. */
    Map<UUID, String> urls(Collection<UUID> mediaIds);

    /** True when the id is a READY image uploaded by this user (the only kind that may become an avatar). */
    boolean readyImageOwnedBy(UUID mediaId, UUID ownerId);
}
