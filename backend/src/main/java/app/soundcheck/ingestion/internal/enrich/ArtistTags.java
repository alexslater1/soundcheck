package app.soundcheck.ingestion.internal.enrich;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Where an artist's crowd-sourced tags come from (Last.fm {@code artist.getTopTags}, or fixtures). */
public interface ArtistTags {

    /** A tag and how many listeners applied it (Last.fm scales the top tag to 100). */
    record Tag(String name, int count) {}

    /** Top tags by MBID when known, else by name; empty when the artist is unknown to the source. */
    List<Tag> topTags(@Nullable UUID mbid, String name, PayloadSink sink);
}
