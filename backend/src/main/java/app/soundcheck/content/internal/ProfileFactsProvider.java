package app.soundcheck.content.internal;

import app.soundcheck.identity.ProfileFacts;
import app.soundcheck.social.SocialGraph;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Answers identity's {@link ProfileFacts}: follow counts from social, the post count from content. */
@Component
@Transactional(readOnly = true)
class ProfileFactsProvider implements ProfileFacts {

    private final SocialGraph social;
    private final PostRepository posts;

    ProfileFactsProvider(SocialGraph social, PostRepository posts) {
        this.social = social;
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
                social.hasBlocked(viewerId, profileUserId),
                social.hasBlocked(profileUserId, viewerId));
    }
}
