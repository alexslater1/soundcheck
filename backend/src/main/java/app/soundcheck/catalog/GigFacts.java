package app.soundcheck.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What another module needs to look a gig up somewhere else (its setlist on setlist.fm):
 * when and where it was, who played (headliner first, with MusicBrainz ids when known), and the
 * setlist.fm id when the gig was added from a setlist.
 */
public record GigFacts(
        UUID id,
        Instant startsAt,
        String venueName,
        @Nullable String venueCity,
        List<Act> acts,
        @Nullable String setlistFmId) {

    public record Act(String name, @Nullable UUID mbid) {}
}
