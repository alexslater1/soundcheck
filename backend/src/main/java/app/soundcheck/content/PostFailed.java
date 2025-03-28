package app.soundcheck.content;

import java.util.UUID;

/** Domain event: a post's clip could not be processed, so the post will never be published. */
public record PostFailed(UUID postId, UUID authorId) {}
