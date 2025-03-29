package app.soundcheck.feed.internal.web;

import app.soundcheck.content.Post;
import app.soundcheck.feed.internal.FollowingFeedService;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Feeds. */
@RestController
@RequestMapping("/api/v1/feed")
@Validated
@Tag(name = "feed", description = "Feeds")
public class FeedController {

    private final FollowingFeedService following;

    public FeedController(FollowingFeedService following) {
        this.following = following;
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
