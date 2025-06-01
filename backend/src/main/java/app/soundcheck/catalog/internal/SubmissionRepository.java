package app.soundcheck.catalog.internal;

import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** SQL for what users add to the catalog: who added a row. */
@Repository
class SubmissionRepository {

    private final DSLContext dsl;

    SubmissionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    void markGigAddedBy(UUID gigId, UUID userId) {
        dsl.update(GIG).set(GIG.CREATED_BY, userId).where(GIG.ID.eq(gigId)).execute();
    }

    void markVenueAddedBy(UUID venueId, UUID userId) {
        dsl.update(VENUE)
                .set(VENUE.CREATED_BY, userId)
                .where(VENUE.ID.eq(venueId))
                .execute();
    }

    void markArtistAddedBy(UUID artistId, UUID userId) {
        dsl.update(ARTIST)
                .set(ARTIST.CREATED_BY, userId)
                .where(ARTIST.ID.eq(artistId))
                .execute();
    }
}
