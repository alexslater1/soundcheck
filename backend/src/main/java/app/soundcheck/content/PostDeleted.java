package app.soundcheck.content;

import java.util.UUID;

/** Domain event: a post was deleted by its author or an admin. */
public record PostDeleted(UUID postId, UUID authorId) {}
