package app.soundcheck.ingestion.internal.source.setlistfm;

import static app.soundcheck.shared.jooq.ingestion.Tables.SETLISTFM_ARTIST_SYNC;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** When each act's past gigs were last read from setlist.fm in full ({@code ingestion.setlistfm_artist_sync}, V160). */
@Repository
@Transactional
public class SetlistFmSyncs {

    private final DSLContext dsl;

    public SetlistFmSyncs(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Every act read so far → when its last read finished. One small table, read once per run. */
    @Transactional(readOnly = true)
    public Map<UUID, Instant> syncedAt() {
        return dsl.select(SETLISTFM_ARTIST_SYNC.ARTIST_ID, SETLISTFM_ARTIST_SYNC.SYNCED_AT)
                .from(SETLISTFM_ARTIST_SYNC)
                .fetchMap(r -> r.value1(), r -> r.value2().toInstant());
    }

    /** The act's setlists are read back to the cutoff and their gigs resolved. */
    public void markSynced(UUID artistId, Instant at, int setlists) {
        OffsetDateTime when = at.atOffset(ZoneOffset.UTC);
        dsl.insertInto(SETLISTFM_ARTIST_SYNC)
                .set(SETLISTFM_ARTIST_SYNC.ARTIST_ID, artistId)
                .set(SETLISTFM_ARTIST_SYNC.SYNCED_AT, when)
                .set(SETLISTFM_ARTIST_SYNC.SETLISTS, setlists)
                .onConflict(SETLISTFM_ARTIST_SYNC.ARTIST_ID)
                .doUpdate()
                .set(SETLISTFM_ARTIST_SYNC.SYNCED_AT, when)
                .set(SETLISTFM_ARTIST_SYNC.SETLISTS, setlists)
                .execute();
    }
}
