package app.soundcheck.social.internal;

import static app.soundcheck.shared.jooq.social.Tables.ARTIST_FOLLOW;
import static app.soundcheck.shared.jooq.social.Tables.VENUE_FOLLOW;

import app.soundcheck.identity.Users;
import app.soundcheck.social.NotifyLevel;
import app.soundcheck.social.PostWatchers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link PostWatchers}: two indexed lookups for the followers, then the block and privacy filters. */
@Service
@Transactional(readOnly = true)
class PostWatcherService implements PostWatchers {

    /** The follow levels that hear about new posts ({@link NotifyLevel#posts()}). */
    static final List<String> POST_LEVELS = Arrays.stream(NotifyLevel.values())
            .filter(NotifyLevel::posts)
            .map(NotifyLevel::name)
            .toList();

    private final DSLContext dsl;
    private final UserGraphRepository graph;
    private final Users users;

    PostWatcherService(DSLContext dsl, UserGraphRepository graph, Users users) {
        this.dsl = dsl;
        this.graph = graph;
        this.users = users;
    }

    @Override
    public List<Watcher> of(UUID authorId, List<UUID> artistIds, @Nullable UUID venueId) {
        Map<UUID, Watcher> byUser = new LinkedHashMap<>();
        if (!artistIds.isEmpty()) {
            // Expected index: artist_follow_artist (artist_id).
            Map<UUID, List<UUID>> fans = new LinkedHashMap<>();
            dsl.select(ARTIST_FOLLOW.ARTIST_ID, ARTIST_FOLLOW.USER_ID)
                    .from(ARTIST_FOLLOW)
                    .where(ARTIST_FOLLOW.ARTIST_ID.in(artistIds))
                    .and(ARTIST_FOLLOW.NOTIFY.in(POST_LEVELS))
                    .forEach(r -> fans.computeIfAbsent(r.value1(), k -> new ArrayList<>())
                            .add(r.value2()));
            for (UUID artistId : artistIds) { // the post's order: its first act wins
                for (UUID user : fans.getOrDefault(artistId, List.of())) {
                    byUser.putIfAbsent(user, new Watcher(user, Kind.ARTIST, artistId));
                }
            }
        }
        if (venueId != null) {
            // Expected index: venue_follow_venue (venue_id).
            dsl.select(VENUE_FOLLOW.USER_ID)
                    .from(VENUE_FOLLOW)
                    .where(VENUE_FOLLOW.VENUE_ID.eq(venueId))
                    .and(VENUE_FOLLOW.NOTIFY.in(POST_LEVELS))
                    .forEach(r -> byUser.putIfAbsent(r.value1(), new Watcher(r.value1(), Kind.VENUE, venueId)));
        }
        byUser.remove(authorId);
        if (byUser.isEmpty()) {
            return List.of();
        }
        Set<UUID> keep = new HashSet<>(byUser.keySet());
        keep.removeAll(graph.blockedAmong(authorId, keep));
        if (!users.privateAmong(List.of(authorId)).isEmpty()) {
            keep.retainAll(graph.followersAmong(authorId, keep)); // only the author's followers
        }
        return byUser.values().stream().filter(w -> keep.contains(w.userId())).toList();
    }
}
