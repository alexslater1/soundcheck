package app.soundcheck.content.internal;

import app.soundcheck.social.ProfileAccess;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Private profiles for posts: a private profile's posts are for its followers. The rule is
 * social's {@link ProfileAccess}; content applies it here, once, for every read of posts (pages, single
 * posts, likes, comments, counts). Two queries per batch of authors, whatever its size.
 */
@Component
class AuthorVisibility {

    private final ProfileAccess access;

    AuthorVisibility(ProfileAccess access) {
        this.access = access;
    }

    /**
     * The authors among {@code authorIds} whose posts the viewer may not see because their profile is
     * private and the viewer does not follow them. The viewer is never hidden from themselves.
     */
    Set<UUID> hiddenAmong(UUID viewerId, Collection<UUID> authorIds) {
        return access.hiddenAmong(viewerId, authorIds);
    }

    boolean hidden(UUID viewerId, UUID authorId) {
        return !hiddenAmong(viewerId, List.of(authorId)).isEmpty();
    }
}
