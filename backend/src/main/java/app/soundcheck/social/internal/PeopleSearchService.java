package app.soundcheck.social.internal;

import app.soundcheck.identity.ProfileVisibility;
import app.soundcheck.identity.UserMatch;
import app.soundcheck.identity.UserSummary;
import app.soundcheck.identity.Users;
import app.soundcheck.social.SocialGraph;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * People in the Search tab. Identity finds the names that match; social, which knows the
 * graph, drops anyone with a block between them and the viewer (a block hides two people from each other)
 * and puts people the viewer follows, then people who follow the viewer, first within each match tier.
 * Private profiles are listed and marked: what they withhold is their content, which is filtered where it
 * is read, so search needs no visibility rule of its own.
 */
@Service
public class PeopleSearchService {

    /** At most this many people, unless the caller asks for fewer. */
    public static final int LIMIT = 10;

    /** Identity's pool before blocks are dropped and the viewer's graph re-ranks it. */
    static final int POOL = 30;

    /** One person found: how they appear in lists, and whether their profile is private. */
    public record PersonResult(UserSummary user, ProfileVisibility profileVisibility) {}

    /** What the ranking compares: identity's match, then the viewer's relation to the person. */
    record Candidate(UserMatch match, boolean followed, boolean followsViewer) {}

    /** The text match first, then someone the viewer follows, then a follower, then similarity. */
    static final Comparator<Candidate> ORDER = Comparator.comparingInt(
                    (Candidate c) -> c.match().tier())
            .thenComparing(Candidate::followed, Comparator.reverseOrder())
            .thenComparing(Candidate::followsViewer, Comparator.reverseOrder())
            .thenComparing(c -> c.match().similarity(), Comparator.reverseOrder());

    /** The pool in ranked order, without blocked people, at most {@code limit}; a stable sort keeps ties. */
    static List<UUID> rank(List<Candidate> candidates, Set<UUID> blocked, int limit) {
        return candidates.stream()
                .filter(c -> !blocked.contains(c.match().id()))
                .sorted(ORDER)
                .limit(limit)
                .map(c -> c.match().id())
                .toList();
    }

    private final Users users;
    private final SocialGraph graph;

    PeopleSearchService(Users users, SocialGraph graph) {
        this.users = users;
        this.graph = graph;
    }

    @Transactional(readOnly = true)
    public List<PersonResult> search(UUID viewerId, String q, int limit) {
        List<UserMatch> matches = users.search(q, POOL, viewerId);
        if (matches.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = matches.stream().map(UserMatch::id).toList();
        Set<UUID> followed = graph.followedAmong(viewerId, ids);
        Set<UUID> followers = graph.followersAmong(viewerId, ids);
        List<UUID> ranked = rank(
                matches.stream()
                        .map(m -> new Candidate(m, followed.contains(m.id()), followers.contains(m.id())))
                        .toList(),
                graph.blockedAmong(viewerId, ids),
                limit);
        Map<UUID, UserSummary> summaries = users.summaries(ranked);
        Set<UUID> privateOnes = users.privateAmong(ranked);
        return ranked.stream()
                .filter(summaries::containsKey)
                .map(id -> new PersonResult(
                        summaries.get(id).withFollowing(followed.contains(id)),
                        privateOnes.contains(id) ? ProfileVisibility.PRIVATE : ProfileVisibility.PUBLIC))
                .toList();
    }
}
