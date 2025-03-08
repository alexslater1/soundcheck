package app.soundcheck.identity.internal;

import app.soundcheck.catalog.GeoPoint;
import app.soundcheck.catalog.ViewerHome;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identity's implementation of the catalog's {@link ViewerHome}: the centroid of the user's chosen home
 * city (never device GPS). The catalog asks through the interface it declares; it never
 * reads identity's tables.
 */
@Component
class HomeLocations implements ViewerHome {

    private final UserRepository users;

    HomeLocations(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GeoPoint> homeOf(UUID userId) {
        return users.homeLocation(userId);
    }
}
