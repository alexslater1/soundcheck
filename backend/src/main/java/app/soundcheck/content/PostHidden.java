package app.soundcheck.content;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event: a moderator hid a post. Nothing listens to it yet: every
 * read path already shows only PUBLISHED posts, so a hidden post is gone without anyone being told. It
 * is published so that a later listener (notify the author, drop the post's signals) needs no change
 * here.
 */
public record PostHidden(UUID postId, UUID authorId, Instant hiddenAt) {}
