package app.soundcheck.feed.internal.web;

import app.soundcheck.content.Post;
import app.soundcheck.feed.HomeFeed;
import app.soundcheck.feed.internal.FollowingFeedService;
import app.soundcheck.feed.internal.HomeFeedService;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.Actors;
import app.soundcheck.shared.web.Page;
import app.soundcheck.shared.web.RateLimitProperties;
import app.soundcheck.shared.web.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Feeds: the ranked home feed and the chronological Following feed. */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1/feed")
@Validated
@Tag(name = "feed", description = "Feeds")
public class FeedController {

    private final FollowingFeedService following;
    private final HomeFeedService home;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    public FeedController(
            FollowingFeedService following, HomeFeedService home, RateLimiter rateLimiter, RateLimitProperties limits) {
        this.following = following;
        this.home = home;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @GetMapping("/home")
    @Operation(
            operationId = "getHomeFeed",
            summary = "The ranked For You feed: without a cursor a new session is built; with one, its next page."
                    + " 410 FEED_SESSION_EXPIRED when the session is gone")
    public HomeFeed home(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit,
            @Parameter(description = "Admins only: include each item's score and contributions")
                    @RequestParam(defaultValue = "false")
                    boolean debug) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (cursor == null) {
            // Building a session is the most expensive request the API has; paging through one is cheap.
            rateLimiter.enforce("feed-build:" + userId, limits.feedBuildsPerMinute(), Duration.ofMinutes(1));
        }
        boolean admin = Actors.isAdmin(jwt);
        return home.home(userId, cursor, limit, debug && admin);
    }

    @GetMapping("/following")
    @Operation(operationId = "getFollowingFeed", summary = "Posts by the people you follow, newest first")
    public Page<Post> following(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return following.following(UUID.fromString(jwt.getSubject()), cursor, limit);
    }
}
