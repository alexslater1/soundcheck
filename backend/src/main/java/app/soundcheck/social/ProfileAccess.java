package app.soundcheck.social;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Who may see a profile's content: its posts, gig stats, gig history and follower lists. This
 * is the one place the rule lives. A profile's content is hidden from a viewer when the profile is
 * private, the viewer is not its owner, and the viewer does not follow it (a pending follow request is not
 * a follow). Blocks are a separate question ({@link SocialGraph#blockedAmong}). Content's
 * {@code AuthorVisibility} and identity's {@code ProfileFacts} answer through it; the feed applies the
 * same rule in SQL ({@code CandidateRepository}).
 */
public interface ProfileAccess {

    /** The owners among {@code ownerIds} whose content the viewer may not see. Two queries, whatever the size. */
    Set<UUID> hiddenAmong(UUID viewerId, Collection<UUID> ownerIds);

    /** True when the viewer may not see {@code ownerId}'s content. */
    boolean hidden(UUID viewerId, UUID ownerId);
}
