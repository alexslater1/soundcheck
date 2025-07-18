package app.soundcheck.content.internal;

import app.soundcheck.catalog.CatalogViews;
import app.soundcheck.catalog.GigSummary;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Pending list: gigs the fan went to (WENT, from "I was there", the attendance buttons or a post that
 * has since gone) that have started and that no open post of theirs comes from yet, newest first, with no
 * time limit. Tapping one starts a post for it.
 */
@Service
@Transactional(readOnly = true)
public class PendingGigs {

    static final int MAX = 100;

    private final CatalogViews catalog;
    private final PostRepository posts;
    private final Clock clock;

    PendingGigs(CatalogViews catalog, PostRepository posts, Clock clock) {
        this.catalog = catalog;
        this.posts = posts;
        this.clock = clock;
    }

    public List<GigSummary> of(UUID userId) {
        List<UUID> went = catalog.attendanceOf(userId).entrySet().stream()
                .filter(mark -> mark.getValue().equals("WENT"))
                .map(Map.Entry::getKey)
                .toList();
        Set<UUID> posted = posts.postedGigs(userId, went);
        Instant now = Instant.now(clock);
        return catalog
                .gigs(userId, went.stream().filter(id -> !posted.contains(id)).toList())
                .values()
                .stream()
                .filter(gig -> !gig.startsAt().isAfter(now))
                .sorted(Comparator.comparing(GigSummary::startsAt).reversed())
                .limit(MAX)
                .toList();
    }
}
