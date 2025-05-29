package app.soundcheck.feed.internal;

import app.soundcheck.catalog.CatalogViews;
import app.soundcheck.catalog.ViewerHome;
import app.soundcheck.content.PostHides;
import app.soundcheck.feed.internal.ranking.GenreProfile;
import app.soundcheck.identity.Users;
import app.soundcheck.signals.Affinities;
import app.soundcheck.signals.Impressions;
import app.soundcheck.social.SocialGraph;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the {@link UserContext}: one call to each module that owns a piece of it. A fixed
 * number of queries, once per session; pages after the first do not need it.
 */
@Component
public class UserContextLoader {

    static final int SEEN_CAP = 2_000;
    static final int HIDDEN_CAP = 2_000;
    static final int AFFINITY_CAP = 200;
    static final int TOP_GENRES = 3;

    private final SocialGraph social;
    private final CatalogViews catalog;
    private final ViewerHome homes;
    private final Users users;
    private final PostHides hides;
    private final Impressions impressions;
    private final Affinities affinities;

    UserContextLoader(
            SocialGraph social,
            CatalogViews catalog,
            ViewerHome homes,
            Users users,
            PostHides hides,
            Impressions impressions,
            Affinities affinities) {
        this.social = social;
        this.catalog = catalog;
        this.homes = homes;
        this.users = users;
        this.hides = hides;
        this.impressions = impressions;
        this.affinities = affinities;
    }

    @Transactional(readOnly = true)
    public UserContext load(UUID userId, int seenWindowDays, Instant now) {
        Map<Short, Double> genreVector = GenreProfile.blend(users.genrePreferences(userId), affinities.genres(userId));
        return new UserContext(
                userId,
                social.followeeIds(userId),
                social.followedArtistIds(userId),
                catalog.attendanceOf(userId),
                genreVector,
                GenreProfile.top(genreVector, TOP_GENRES),
                homes.homeOf(userId).orElse(null),
                social.blockedEitherWay(userId),
                hides.hiddenBy(userId, HIDDEN_CAP),
                impressions.seenBy(userId, now.minus(Duration.ofDays(seenWindowDays)), SEEN_CAP),
                affinities.artists(userId, AFFINITY_CAP),
                affinities.authors(userId, AFFINITY_CAP));
    }
}
