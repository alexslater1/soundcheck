package app.soundcheck.ingestion.internal.enrich;

import java.util.Optional;
import java.util.UUID;

/** Finds an artist's MusicBrainz id by name (MusicBrainz search, or fixtures). */
public interface MbidLookup {

    /** The id of the best hit, if its score reaches the configured minimum (95). */
    Optional<UUID> findMbid(String name, PayloadSink sink);
}
