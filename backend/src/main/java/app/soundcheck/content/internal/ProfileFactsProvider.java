package app.soundcheck.content.internal;

import app.soundcheck.identity.ProfileFacts;
import app.soundcheck.social.ProfileAccess;
import app.soundcheck.social.SocialGraph;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers identity's {@link ProfileFacts}: follow counts and the viewer's relation from social (including
 * the private-profile rule, {@link ProfileAccess}), the post count from content.
 */
@Component
@Transactional(readOnly = true)
class ProfileFactsProvider implements ProfileFacts {

    private final SocialGraph social;
    private final ProfileAccess access;
    private final PostRepository posts;

    ProfileFactsProvider(SocialGraph social, ProfileAccess access, PostRepository posts) {
        this.social = social;
        this.access = access;
        this.posts = posts;
    }

    @Override
    public Counts counts(UUID userId) {
        return new Counts(social.followerCount(userId), social.followingCount(userId), posts.countPublished(userId));
    }

    @Override
    public Relation relation(UUID viewerId, UUID profileUserId) {
        return new Relation(
                !social.followedAmong(viewerId, List.of(profileUserId)).isEmpty(),
                !social.requestedAmong(viewerId, List.of(profileUserId)).isEmpty(),
                social.hasBlocked(viewerId, profileUserId),
                social.hasBlocked(profileUserId, viewerId),
                access.hidden(viewerId, profileUserId));
    }
}
