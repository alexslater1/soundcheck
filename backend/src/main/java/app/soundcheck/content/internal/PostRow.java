package app.soundcheck.content.internal;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A row of {@code content.post} as the content module uses it. */
public record PostRow(
        UUID id,
        UUID authorId,
        UUID mediaId,
        @Nullable UUID gigId,
        @Nullable String caption,
        String status,
        int likeCount,
        int commentCount,
        Instant createdAt,
        @Nullable Instant publishedAt) {

    public boolean published() {
        return status.equals("PUBLISHED");
    }
}
