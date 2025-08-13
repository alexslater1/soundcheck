package app.soundcheck.catalog;

import java.util.Collection;
import java.util.UUID;

/**
 * A fan's live posts from a gig mark them as having gone, so the gig counts in their
 * stats. The content module calls this when a post goes live and when one is deleted; the catalog keeps
 * the marks and why each exists ({@code gig_attendance.source}: USER when the fan said so, POST when
 * only their posts did).
 */
public interface PostAttendance {

    /**
     * A post of the user's from the gig went live: they are marked WENT (source POST), an INTERESTED or
     * GOING mark included. A WENT stays as it is, so a mark the fan made stays theirs. Nothing happens for
     * a gig whose day has not come on the UK calendar. Safe to repeat. The acts their live clips are of
     * count as seen, if the fan had unticked them: you filmed them, so you saw them. An act
     * filmed that is not on the gig's bill is added to it as the fan's act, logged like an edit to the bill.
     */
    void postWentLive(UUID userId, UUID gigId, Collection<UUID> actsFilmed);

    /**
     * The user has no live post of the gig any more: their WENT is removed if only posts made it (source
     * POST); one they set themselves stays. Safe to repeat.
     */
    void lastPostGone(UUID userId, UUID gigId);
}
