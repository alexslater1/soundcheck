package app.soundcheck.identity;

/**
 * Who may see a profile's posts, gig stats and gig history. {@link #PUBLIC} is the
 * default: every signed-in user (blocks still apply). {@link #PRIVATE}: only the people the owner follows,
 * without follow requests; the owner's posts also stay out of everyone else's feeds. The owner always sees
 * their own.
 */
public enum ProfileVisibility {
    PUBLIC,
    PRIVATE
}
