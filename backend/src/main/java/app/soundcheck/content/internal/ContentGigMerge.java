package app.soundcheck.content.internal;

import static app.soundcheck.shared.jooq.content.Tables.GIG_COMMENT;
import static app.soundcheck.shared.jooq.content.Tables.POST;

import app.soundcheck.catalog.GigMergeParticipant;
import app.soundcheck.identity.UserSummary;
import app.soundcheck.identity.Users;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * Content's part of a gig merge: posts and gig comments move to the surviving gig.
 *
 * <p>The one conflict: an author with an open post at both gigs for the same day. {@code
 * post_author_gig_day} (V096) allows one live or pending post per author, gig and day, so moving the
 * first would break it; the operator sorts that out (deletes or hides one) and merges again.
 */
@Component
class ContentGigMerge implements GigMergeParticipant {

    /** "Open", exactly as {@code post_author_gig_day}'s {@code where} clause defines it. */
    static final List<String> OPEN = List.of("PUBLISHED", "PENDING_MEDIA");

    private final DSLContext dsl;
    private final Users users;
    private final Clock clock;

    ContentGigMerge(DSLContext dsl, Users users, Clock clock) {
        this.dsl = dsl;
        this.users = users;
        this.clock = clock;
    }

    /** Expected indexes: post_gig (V150) and gig_comment_gig (gig_id, created_at, id). */
    @Override
    public Map<String, Long> countsAt(UUID gigId) {
        long posts = dsl.fetchCount(POST, POST.GIG_ID.eq(gigId));
        long comments = dsl.fetchCount(GIG_COMMENT, GIG_COMMENT.GIG_ID.eq(gigId).and(GIG_COMMENT.DELETED_AT.isNull()));
        return Map.of(POSTS, posts, GIG_COMMENTS, comments);
    }

    /**
     * Expected indexes: post_gig for {@code from}'s posts, then post_author_gig_day (author_id, gig_id,
     * day) for the matching post at {@code into}.
     */
    @Override
    public List<String> conflicts(UUID from, UUID into) {
        var moving = POST.as("moving");
        var staying = POST.as("staying");
        var clashes = dsl.selectDistinct(moving.AUTHOR_ID, moving.DAY)
                .from(moving)
                .join(staying)
                .on(staying.AUTHOR_ID.eq(moving.AUTHOR_ID))
                .and(staying.GIG_ID.eq(into))
                .and(staying.DAY.eq(moving.DAY))
                .and(staying.STATUS.in(OPEN))
                .where(moving.GIG_ID.eq(from))
                .and(moving.STATUS.in(OPEN))
                .orderBy(moving.DAY, moving.AUTHOR_ID)
                .fetch();
        if (clashes.isEmpty()) {
            return List.of();
        }
        Map<UUID, UserSummary> authors =
                users.summaries(clashes.stream().map(r -> r.value1()).toList());
        return clashes.stream()
                .map(r -> {
                    UserSummary author = authors.get(r.value1());
                    String who = author == null ? "Account " + r.value1() : "@" + author.handle();
                    return who + " has an open post at both gigs for " + r.value2()
                            + ": delete or hide one of them first";
                })
                .toList();
    }

    /** Every post and gig comment, whatever its status: nothing is left for the gig's delete to trip on. */
    @Override
    public void moveTo(UUID from, UUID into) {
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        dsl.update(POST)
                .set(POST.GIG_ID, into)
                .set(POST.UPDATED_AT, now)
                .where(POST.GIG_ID.eq(from))
                .execute();
        dsl.update(GIG_COMMENT)
                .set(GIG_COMMENT.GIG_ID, into)
                .where(GIG_COMMENT.GIG_ID.eq(from))
                .execute();
    }
}
