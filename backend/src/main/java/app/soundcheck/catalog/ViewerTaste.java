package app.soundcheck.catalog;

import java.util.Set;
import java.util.UUID;

/**
 * What the catalog needs to know about a user's taste to rank search suggestions "by you":
 * the genres they chose. Declared here and implemented by identity, like {@link ViewerHome}, so the
 * dependency keeps pointing identity → catalog.
 */
public interface ViewerTaste {

    /** The ids of the genres the user chose at sign-up or in settings; empty if none. */
    Set<Short> genresOf(UUID userId);
}
