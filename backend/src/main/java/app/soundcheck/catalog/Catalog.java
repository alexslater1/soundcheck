package app.soundcheck.catalog;

import java.util.UUID;

/** Catalog's read API for other modules (ids only for now). */
public interface Catalog {

    boolean artistExists(UUID artistId);

    boolean gigExists(UUID gigId);
}
