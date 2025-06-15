package app.soundcheck.identity.internal;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A row of {@code identity.app_user} as the identity module uses it internally. */
public record UserRecord(
        UUID id,
        String email,
        String handle,
        String displayName,
        @Nullable String bio,
        String role,
        String passwordHash,
        @Nullable Integer homeCityId,
        @Nullable Instant onboardedAt,
        Instant createdAt,
        @Nullable Instant deletedAt,
        /** Other users may see this user's gig stats. */
        boolean showGigStats) {

    public boolean deleted() {
        return deletedAt != null;
    }
}
