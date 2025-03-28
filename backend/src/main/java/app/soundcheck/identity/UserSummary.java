package app.soundcheck.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * How a user appears next to a post, a comment or in a list.
 * {@code isFollowing} is relative to the viewer; identity does not know it and leaves it null, the
 * module that does (social, through content or feed) fills it in with {@link #withFollowing}.
 */
public record UserSummary(
        UUID id,
        String handle,
        String displayName,
        @Schema(nullable = true) String avatarUrl,
        @Schema(nullable = true) Boolean isFollowing) {

    public UserSummary withFollowing(boolean following) {
        return new UserSummary(id, handle, displayName, avatarUrl, following);
    }
}
