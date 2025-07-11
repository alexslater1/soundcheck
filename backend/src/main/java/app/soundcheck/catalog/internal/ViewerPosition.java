package app.soundcheck.catalog.internal;

import app.soundcheck.catalog.GeoPoint;
import app.soundcheck.shared.web.ApiException;
import org.jspecify.annotations.Nullable;

/**
 * Where the viewer is, used only as the point distances are measured from. Once the user
 * allows location, the app sends its position ({@code fromLat}, {@code fromLng}) with each request whose
 * answer shows distances; without it, distances are measured from the home city's centre as before. The
 * search area never moves with it: "Near London" stays 25 km around London.
 *
 * <p>The position is rounded here to 0.01° (about 1.1 km north–south and 0.7 km east–west in the UK)
 * whatever the app sent, so nothing finer is ever used. It lives for one request and is never stored.
 */
public final class ViewerPosition {

    /** Two decimal places of a degree: about a kilometre. */
    static final double PER_DEGREE = 100.0;

    private ViewerPosition() {}

    /** The rounded position, or null when the request sent none; one coordinate alone is a 400. */
    public static @Nullable GeoPoint approximate(@Nullable Double lat, @Nullable Double lng) {
        if (lat == null && lng == null) {
            return null;
        }
        if (lat == null || lng == null) {
            throw ApiException.badRequest("LOCATION_INCOMPLETE", "Send both fromLat and fromLng, or neither");
        }
        return new GeoPoint(round(lat), round(lng));
    }

    static double round(double degrees) {
        return Math.round(degrees * PER_DEGREE) / PER_DEGREE;
    }
}
