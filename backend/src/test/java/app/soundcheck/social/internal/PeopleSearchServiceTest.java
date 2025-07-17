package app.soundcheck.social.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.identity.UserMatch;
import app.soundcheck.social.internal.PeopleSearchService.Candidate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PeopleSearchServiceTest {

    static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID(), D = UUID.randomUUID();

    @Test
    void theTextMatchComesBeforeTheGraph() {
        // A stranger with the exact handle beats someone the viewer follows whose handle only starts so.
        List<UUID> ranked = PeopleSearchService.rank(
                List.of(
                        new Candidate(new UserMatch(A, 1, 0.9), true, true),
                        new Candidate(new UserMatch(B, 0, 1.0), false, false)),
                Set.of(),
                10);
        assertThat(ranked).containsExactly(B, A);
    }

    @Test
    void withinATierFollowedThenFollowersThenSimilarity() {
        List<UUID> ranked = PeopleSearchService.rank(
                List.of(
                        new Candidate(new UserMatch(A, 1, 0.9), false, false),
                        new Candidate(new UserMatch(B, 1, 0.2), false, true),
                        new Candidate(new UserMatch(C, 1, 0.1), true, false),
                        new Candidate(new UserMatch(D, 1, 0.95), false, false)),
                Set.of(),
                10);
        assertThat(ranked).containsExactly(C, B, D, A);
    }

    @Test
    void blockedPeopleAreDroppedBeforeTheLimit() {
        List<UUID> ranked = PeopleSearchService.rank(
                List.of(
                        new Candidate(new UserMatch(A, 0, 1.0), false, false),
                        new Candidate(new UserMatch(B, 1, 0.5), false, false),
                        new Candidate(new UserMatch(C, 2, 0.5), false, false)),
                Set.of(A),
                2);
        assertThat(ranked).containsExactly(B, C);
    }
}
