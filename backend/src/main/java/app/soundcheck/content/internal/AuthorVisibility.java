package app.soundcheck.content.internal;

import app.soundcheck.identity.Users;
import app.soundcheck.social.SocialGraph;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Private profiles for posts: a private profile's posts are for the people its owner
 * follows. Identity knows who is private and social who follows whom; content may ask both, so the rule is
 * applied here, once, for every read of posts (pages, single posts, likes, comments, counts). Two queries
 * per batch of authors, whatever its size.
 */
@Component
class AuthorVisibility {

    private final Users users;
    private final SocialGraph social;

    AuthorVisibility(Users users, SocialGraph social) {
        this.users = users;
        this.social = social;
    }

    /**
     * The authors among {@code authorIds} whose posts the viewer may not see because their profile is
     * private and they do not follow the viewer. The viewer is never hidden from themselves.
     */
    Set<UUID> hiddenAmong(UUID viewerId, Collection<UUID> authorIds) {
        Set<UUID> others = new HashSet<>(authorIds);
        others.remove(viewerId);
        Set<UUID> hidden = new HashSet<>(users.privateAmong(others));
        if (!hidden.isEmpty()) {
            hidden.removeAll(social.followersAmong(viewerId, hidden));
        }
        return hidden;
    }

    boolean hidden(UUID viewerId, UUID authorId) {
        return !hiddenAmong(viewerId, List.of(authorId)).isEmpty();
    }
}
