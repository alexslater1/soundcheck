package app.soundcheck.content.internal;

import static app.soundcheck.shared.jooq.content.Tables.COMMENT;
import static app.soundcheck.shared.jooq.content.Tables.POST;
import static app.soundcheck.shared.jooq.content.Tables.POST_LIKE;
import static app.soundcheck.shared.jooq.content.Tables.REPORT;

import app.soundcheck.identity.AccountPurgeStep;
import app.soundcheck.media.MediaLibrary;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Content's part of an account purge: the user's likes and comments on other people's posts (with the
 * counters put right), their reports, their own posts (whose likes, comments and tags go with them), and
 * finally the clips themselves, which only the posts referred to.
 */
@Component
class ContentPurge implements AccountPurgeStep {

    private final DSLContext dsl;
    private final MediaLibrary media;

    ContentPurge(DSLContext dsl, MediaLibrary media) {
        this.dsl = dsl;
        this.media = media;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void purge(UUID userId) {
        // Likes on other people's posts: the rows would cascade with the user, but the counters would not.
        dsl.update(POST)
                .set(POST.LIKE_COUNT, POST.LIKE_COUNT.minus(1))
                .where(POST.ID.in(DSL.select(POST_LIKE.POST_ID).from(POST_LIKE).where(POST_LIKE.USER_ID.eq(userId))))
                .execute();
        dsl.deleteFrom(POST_LIKE).where(POST_LIKE.USER_ID.eq(userId)).execute();

        // Comments: one decrement per live comment, then the rows (reports on them cascade).
        var live = DSL.select(COMMENT.POST_ID, DSL.count().as("n"))
                .from(COMMENT)
                .where(COMMENT.AUTHOR_ID.eq(userId))
                .and(COMMENT.DELETED_AT.isNull())
                .groupBy(COMMENT.POST_ID)
                .asTable("live");
        dsl.update(POST)
                .set(POST.COMMENT_COUNT, POST.COMMENT_COUNT.minus(live.field("n", Integer.class)))
                .from(live)
                .where(POST.ID.eq(live.field(COMMENT.POST_ID)))
                .execute();
        dsl.deleteFrom(COMMENT).where(COMMENT.AUTHOR_ID.eq(userId)).execute();

        dsl.deleteFrom(REPORT).where(REPORT.REPORTER_ID.eq(userId)).execute();

        // The user's own posts; post_artist, likes, comments, hides and reports on them cascade.
        dsl.deleteFrom(POST).where(POST.AUTHOR_ID.eq(userId)).execute();

        // Nothing refers to the clips any more: remove the objects and the asset rows.
        media.purgeOwnedBy(userId);
    }
}
