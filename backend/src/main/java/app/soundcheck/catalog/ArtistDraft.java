package app.soundcheck.catalog;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An artist to create ({@code catalog.artist}). {@code genreWeights} (genre id → weight in
 * 0..1) may be empty; enrichment fills or replaces it later.
 */
public record ArtistDraft(
        String name, @Nullable UUID mbid, @Nullable String imageUrl, Map<Short, Float> genreWeights) {}
