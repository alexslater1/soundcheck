package app.soundcheck.catalog;

import java.util.UUID;

/**
 * What the catalog needs to know about posts to answer {@code GigDetail.postsCount}: how many published
 * clips a gig has. Catalog only declares this interface; the content module, which owns posts and may
 * depend on catalog, implements it (the same inversion as {@link ViewerHome}).
 */
public interface GigClips {

    long countAt(UUID gigId);
}
