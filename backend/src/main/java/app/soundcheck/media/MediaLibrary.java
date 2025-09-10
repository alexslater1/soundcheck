package app.soundcheck.media;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Media's read API for other modules (content attaches clips to posts and shows them). Batch methods take
 * many ids so a page of posts costs one query, not one per post.
 */
public interface MediaLibrary {

    Optional<MediaInfo> find(UUID mediaId);

    /** Facts for the ids that exist. */
    Map<UUID, MediaInfo> findAll(Collection<UUID> mediaIds);

    /** Playback info for the ids that exist, whatever their status. */
    Map<UUID, Playback> playbacks(Collection<UUID> mediaIds);

    /**
     * Lets go of assets nothing will show again (a deleted post's clips and preview frames): they
     * become DELETED with {@code deleted_at} set, in the caller's transaction, so they go if and only if
     * the delete commits. Their files stay in storage for 7 days, then {@code MediaObjectPurge} removes
     * them; the rows stay, because the post's clips still reference them. Assets already DELETED, and
     * unknown ids, are left alone, so a repeat changes nothing.
     *
     * @return how many assets were released by this call
     */
    int release(Collection<UUID> mediaIds);

    /**
     * Removes everything a user ever uploaded, for good: the originals, everything served for them, and
     * the rows (account purge). The caller must have removed whatever refers to these assets first.
     * Safe to repeat.
     *
     * @return how many assets were removed
     */
    int purgeOwnedBy(UUID ownerId);
}
