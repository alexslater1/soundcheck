package app.soundcheck.content.internal;

import static app.soundcheck.shared.jooq.content.Tables.POST_PERSON;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * jOOQ access to {@code content.post_person}: the people tagged in posts. TAGGED: the author
 * tagged them; ON_PROFILE: they put the post on their profile (or added clips to it); REMOVED: they took the
 * tag off, and the author cannot tag them on that post again.
 */
@Repository
public class TagRepository {

    /** One tagged person of a post. */
    public record Tag(UUID postId, UUID userId, String status) {

        public boolean shown() {
            return !status.equals("REMOVED");
        }
    }

    private final DSLContext dsl;

    TagRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Each post's tags, oldest first, REMOVED included. Expected index: the primary key (post_id, user_id). */
    public Map<UUID, List<Tag>> of(Collection<UUID> postIds) {
        Map<UUID, List<Tag>> byPost = new LinkedHashMap<>();
        if (postIds.isEmpty()) {
            return byPost;
        }
        dsl.selectFrom(POST_PERSON)
                .where(POST_PERSON.POST_ID.in(postIds))
                .orderBy(POST_PERSON.CREATED_AT, POST_PERSON.USER_ID)
                .forEach(r -> byPost.computeIfAbsent(r.getPostId(), id -> new ArrayList<>())
                        .add(new Tag(r.getPostId(), r.getUserId(), r.getStatus())));
        return byPost;
    }

    public Optional<Tag> find(UUID postId, UUID userId) {
        return dsl.selectFrom(POST_PERSON)
                .where(POST_PERSON.POST_ID.eq(postId))
                .and(POST_PERSON.USER_ID.eq(userId))
                .fetchOptional(r -> new Tag(r.getPostId(), r.getUserId(), r.getStatus()));
    }

    /** Tags the user (TAGGED) unless they already have a row: a REMOVED tag stays removed. */
    public boolean tag(UUID postId, UUID userId, Instant now) {
        return dsl.insertInto(POST_PERSON)
                        .set(POST_PERSON.POST_ID, postId)
                        .set(POST_PERSON.USER_ID, userId)
                        .set(POST_PERSON.STATUS, "TAGGED")
                        .set(POST_PERSON.CREATED_AT, at(now))
                        .set(POST_PERSON.UPDATED_AT, at(now))
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    public void setStatus(UUID postId, UUID userId, String status, Instant now) {
        dsl.update(POST_PERSON)
                .set(POST_PERSON.STATUS, status)
                .set(POST_PERSON.UPDATED_AT, at(now))
                .where(POST_PERSON.POST_ID.eq(postId))
                .and(POST_PERSON.USER_ID.eq(userId))
                .execute();
    }

    /** The author untags someone: the row goes (they may be tagged again). A REMOVED row stays. */
    public void untag(UUID postId, UUID userId) {
        dsl.deleteFrom(POST_PERSON)
                .where(POST_PERSON.POST_ID.eq(postId))
                .and(POST_PERSON.USER_ID.eq(userId))
                .and(POST_PERSON.STATUS.ne("REMOVED"))
                .execute();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
