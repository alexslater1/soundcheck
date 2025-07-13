package app.soundcheck.social.internal;

import app.soundcheck.identity.Users;
import app.soundcheck.social.ProfileAccess;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The private-profile rule: identity knows who is private, social who follows whom, so the rule
 * is answered here, once. Two queries per batch of owners: who is private, then which of those the viewer
 * follows.
 */
@Service
@Transactional(readOnly = true)
class ProfileAccessService implements ProfileAccess {

    private final Users users;
    private final UserGraphRepository graph;

    ProfileAccessService(Users users, UserGraphRepository graph) {
        this.users = users;
        this.graph = graph;
    }

    @Override
    public Set<UUID> hiddenAmong(UUID viewerId, Collection<UUID> ownerIds) {
        Set<UUID> others = new HashSet<>(ownerIds);
        others.remove(viewerId);
        Set<UUID> hidden = new HashSet<>(users.privateAmong(others));
        if (!hidden.isEmpty()) {
            hidden.removeAll(graph.followedAmong(viewerId, hidden));
        }
        return hidden;
    }

    @Override
    public boolean hidden(UUID viewerId, UUID ownerId) {
        return !hiddenAmong(viewerId, List.of(ownerId)).isEmpty();
    }
}
