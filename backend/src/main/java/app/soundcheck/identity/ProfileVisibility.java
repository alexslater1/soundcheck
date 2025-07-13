package app.soundcheck.identity;

/**
 * Who may see a profile's posts, gig stats and gig history. {@link #PUBLIC} is the
 * default: every signed-in user (blocks still apply). {@link #PRIVATE}: only the owner's followers, and
 * following takes the owner's approval (a follow request); the owner's posts also stay out of everyone
 * else's feeds. The owner always sees their own.
 */
public enum ProfileVisibility {
    PUBLIC,
    PRIVATE
}
