package app.soundcheck.feed.internal;

import app.soundcheck.content.Post;
import app.soundcheck.content.PostFeed;
import app.soundcheck.shared.web.Page;
import app.soundcheck.social.SocialGraph;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Following feed: posts by the people the viewer follows, newest first. No ranking;
 * the ranked "For You" feed sits next to this one behind the same pager in the app.
 */
@Service
@Transactional(readOnly = true)
public class FollowingFeedService {

    private final SocialGraph social;
    private final PostFeed posts;

    FollowingFeedService(SocialGraph social, PostFeed posts) {
        this.social = social;
        this.posts = posts;
    }

    public Page<Post> following(UUID viewerId, @Nullable String cursor, @Nullable Integer limit) {
        return posts.byAuthors(viewerId, social.followeeIds(viewerId), cursor, limit);
    }
}
