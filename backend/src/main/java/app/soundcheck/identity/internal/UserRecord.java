package app.soundcheck.identity.internal;

import app.soundcheck.identity.ProfileVisibility;
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
        /** Who may see this user's posts, gig stats and gig history. */
        ProfileVisibility profileVisibility) {

    public boolean deleted() {
        return deletedAt != null;
    }

    public boolean privateProfile() {
        return profileVisibility == ProfileVisibility.PRIVATE;
    }
}
