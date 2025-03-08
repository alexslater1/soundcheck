package app.soundcheck.catalog;

import java.util.Optional;
import java.util.UUID;

/**
 * What the catalog needs to know about a user to answer "gigs near me" without coordinates in the
 * request: their home city's centroid. Catalog only declares this interface; the identity module, which
 * owns users, implements it. Catalog therefore depends on nothing but {@code shared}, and
 * identity → catalog is a direction the module rules already allow.
 */
public interface ViewerHome {

    Optional<GeoPoint> homeOf(UUID userId);
}
