package app.soundcheck.content.internal;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A row of {@code content.post_clip}: one slot of a post. {@code mediaId} is null until the
 * clip's upload is complete; {@code previewMediaId} is an image of a frame, shown blurred meanwhile.
 */
public record ClipRow(
        UUID id,
        UUID postId,
        UUID ownerId,
        int position,
        @Nullable UUID artistId,
        @Nullable UUID mediaId,
        @Nullable UUID previewMediaId,
        Instant createdAt) {}
