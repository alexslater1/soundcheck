package app.soundcheck.catalog;

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
}
