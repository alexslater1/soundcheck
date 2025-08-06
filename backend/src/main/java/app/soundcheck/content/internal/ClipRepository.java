package app.soundcheck.content.internal;

import static app.soundcheck.shared.jooq.content.Tables.POST_CLIP;

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
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** jOOQ access to {@code content.post_clip}: the clips (slots) of posts. */
@Repository
public class ClipRepository {

    private final DSLContext dsl;

    ClipRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(ClipRow clip) {
        dsl.insertInto(POST_CLIP)
                .set(POST_CLIP.ID, clip.id())
                .set(POST_CLIP.POST_ID, clip.postId())
                .set(POST_CLIP.OWNER_ID, clip.ownerId())
                .set(POST_CLIP.POSITION, clip.position())
                .set(POST_CLIP.ARTIST_ID, clip.artistId())
                .set(POST_CLIP.SONG, clip.song())
                .set(POST_CLIP.MEDIA_ID, clip.mediaId())
                .set(POST_CLIP.PREVIEW_MEDIA_ID, clip.previewMediaId())
                .set(POST_CLIP.CREATED_AT, at(clip.createdAt()))
                .execute();
    }

    public Optional<ClipRow> find(UUID clipId) {
        return dsl.selectFrom(POST_CLIP).where(POST_CLIP.ID.eq(clipId)).fetchOptional(ClipRepository::toRow);
    }

    /** Expected index: the unique constraint on media_id. */
    public Optional<ClipRow> findByMedia(UUID mediaId) {
        return dsl.selectFrom(POST_CLIP).where(POST_CLIP.MEDIA_ID.eq(mediaId)).fetchOptional(ClipRepository::toRow);
    }

    /** A post's clips in position order. Expected index: post_clip_post (post_id, position). */
    public List<ClipRow> of(UUID postId) {
        return dsl.selectFrom(POST_CLIP)
                .where(POST_CLIP.POST_ID.eq(postId))
                .orderBy(POST_CLIP.POSITION, POST_CLIP.CREATED_AT, POST_CLIP.ID)
                .fetch(ClipRepository::toRow);
    }

    /** The clips of each post, in position order. Expected index: post_clip_post. */
    public Map<UUID, List<ClipRow>> of(Collection<UUID> postIds) {
        Map<UUID, List<ClipRow>> byPost = new LinkedHashMap<>();
        if (postIds.isEmpty()) {
            return byPost;
        }
        dsl.selectFrom(POST_CLIP)
                .where(POST_CLIP.POST_ID.in(postIds))
                .orderBy(POST_CLIP.POSITION, POST_CLIP.CREATED_AT, POST_CLIP.ID)
                .forEach(r -> byPost.computeIfAbsent(r.getPostId(), id -> new ArrayList<>())
                        .add(toRow(r)));
        return byPost;
    }

    /** The slot gets its clip; false when it already has another one (or is gone). */
    public boolean attach(UUID clipId, UUID mediaId) {
        return dsl.update(POST_CLIP)
                        .set(POST_CLIP.MEDIA_ID, mediaId)
                        .where(POST_CLIP.ID.eq(clipId))
                        .and(POST_CLIP.MEDIA_ID.isNull())
                        .execute()
                == 1;
    }

    public void setPreview(UUID clipId, UUID previewMediaId) {
        dsl.update(POST_CLIP)
                .set(POST_CLIP.PREVIEW_MEDIA_ID, previewMediaId)
                .where(POST_CLIP.ID.eq(clipId))
                .execute();
    }

    public void place(UUID clipId, int position, @Nullable UUID artistId, @Nullable String song) {
        dsl.update(POST_CLIP)
                .set(POST_CLIP.POSITION, position)
                .set(POST_CLIP.ARTIST_ID, artistId)
                .set(POST_CLIP.SONG, song)
                .where(POST_CLIP.ID.eq(clipId))
                .execute();
    }

    public boolean delete(UUID clipId) {
        return dsl.deleteFrom(POST_CLIP).where(POST_CLIP.ID.eq(clipId)).execute() == 1;
    }

    /** The post's highest position, or -1 for a post without clips. */
    public int lastPosition(UUID postId) {
        Integer max = dsl.select(DSL.max(POST_CLIP.POSITION))
                .from(POST_CLIP)
                .where(POST_CLIP.POST_ID.eq(postId))
                .fetchOne(0, Integer.class);
        return max == null ? -1 : max;
    }

    /**
     * Slots that never got their clip, announced before {@code before}. Expected index: post_clip_waiting
     * (created_at) where media_id is null.
     */
    public List<ClipRow> waitingSince(Instant before, int limit) {
        return dsl.selectFrom(POST_CLIP)
                .where(POST_CLIP.MEDIA_ID.isNull())
                .and(POST_CLIP.CREATED_AT.lt(at(before)))
                .orderBy(POST_CLIP.CREATED_AT)
                .limit(limit)
                .fetch(ClipRepository::toRow);
    }

    /** Removes everything the user filmed in other people's posts (account purge). */
    public void deleteOwnedBy(UUID ownerId) {
        dsl.deleteFrom(POST_CLIP).where(POST_CLIP.OWNER_ID.eq(ownerId)).execute();
    }

    static ClipRow toRow(Record r) {
        return new ClipRow(
                r.get(POST_CLIP.ID),
                r.get(POST_CLIP.POST_ID),
                r.get(POST_CLIP.OWNER_ID),
                r.get(POST_CLIP.POSITION),
                r.get(POST_CLIP.ARTIST_ID),
                r.get(POST_CLIP.SONG),
                r.get(POST_CLIP.MEDIA_ID),
                r.get(POST_CLIP.PREVIEW_MEDIA_ID),
                r.get(POST_CLIP.CREATED_AT).toInstant());
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
