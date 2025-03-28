package app.soundcheck.content;

import java.util.UUID;

/** Domain event: a comment was added to a post. */
public record CommentAdded(UUID postId, UUID postAuthorId, UUID commentId, UUID commenterId) {}
