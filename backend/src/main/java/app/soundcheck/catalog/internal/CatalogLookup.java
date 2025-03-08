package app.soundcheck.catalog.internal;

import app.soundcheck.catalog.Catalog;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catalog's read API for other modules. A separate bean from {@link CatalogService} on purpose: social's
 * follow service needs "does this artist exist?" from catalog, while {@code CatalogService} needs "who
 * follows whom?" from social — as one bean each, the two would need each other to be constructed.
 */
@Component
@Transactional(readOnly = true)
class CatalogLookup implements Catalog {

    private final CatalogReadRepository repository;

    CatalogLookup(CatalogReadRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean artistExists(UUID artistId) {
        return repository.artistExists(artistId);
    }

    @Override
    public boolean gigExists(UUID gigId) {
        return repository.gigExists(gigId);
    }
}
