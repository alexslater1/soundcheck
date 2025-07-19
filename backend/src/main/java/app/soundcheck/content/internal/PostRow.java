package app.soundcheck.content.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A row of {@code content.post} as the content module uses it; its clips are {@link ClipRow}s. */
public record PostRow(
        UUID id,
        UUID authorId,
        @Nullable UUID gigId,
        @Nullable LocalDate day,
        @Nullable String caption,
        String status,
        int likeCount,
        int commentCount,
        Instant createdAt,
        @Nullable Instant publishedAt) {

    public boolean published() {
        return status.equals("PUBLISHED");
    }

    /** Still there for its author: not deleted, and not failed for good. */
    public boolean open() {
        return status.equals("PUBLISHED") || status.equals("PENDING_MEDIA");
    }
}
