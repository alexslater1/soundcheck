package app.soundcheck.ingestion.internal.source;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One act on a bill as the source describes it; {@code genres} are the source's own labels.
 * {@code imageUrl} feeds {@code catalog.artist.image_url}.
 */
public record SourceArtist(
        String externalId, String name, @Nullable UUID mbid, List<String> genres, @Nullable String imageUrl) {}
