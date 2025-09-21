package app.soundcheck.catalog;

import java.util.Set;
import java.util.UUID;

/**
 * What the catalog needs to know about posts to answer {@code GigDetail.postsCount}: how many published
 * clips a gig has. Catalog only declares this interface; the content module, which owns posts and may
 * depend on catalog, implements it (the same inversion as {@link ViewerHome}).
 */
public interface GigClips {

    /** Every published clip of the gig, whoever may see it. */
    long countAt(UUID gigId);

    /**
     * How many of those the viewer cannot see because their authors' profiles are private: the
     * gig page says "+3 clips from private profiles" so a busy night still looks busy, with no previews
     * and no names.
     */
    long privateAt(UUID viewerId, UUID gigId);

    /**
     * Clips at the gig, in posts of any status, whose act is this artist: an act on clips can't
     * be taken off the bill by undoing the edit that added it.
     */
    long clipsOfActAt(UUID gigId, UUID artistId);

    /**
     * Every gig with at least one published clip (the setlist.fm backfill looks up the past
     * gigs of the acts on these gigs' bills).
     */
    Set<UUID> gigsWithClips();
}
