package app.soundcheck.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** An artist in a list; {@code genres} are genre slugs, heaviest first (at most three). */
public record ArtistSummary(
        UUID id, String name, @Schema(nullable = true) String imageUrl, List<String> genres, boolean isFollowing) {}
