package app.soundcheck.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.internal.SuggestionService.Candidate;
import app.soundcheck.catalog.internal.SuggestionService.Kind;
import app.soundcheck.catalog.internal.SuggestionService.Suggestion;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The order of the Gigs search suggestions: match first, then the viewer, then activity. */
class SuggestionRankingTest {

    private static Candidate candidate(String name, int tier, int personal, int upcoming, double similarity) {
        return new Candidate(
                new Suggestion(Kind.ARTIST, UUID.randomUUID(), name, null, null, upcoming), tier, personal, similarity);
    }

    private static List<String> ranked(Candidate... candidates) {
        return SuggestionService.rank(List.of(candidates), 8).stream()
                .map(Suggestion::name)
                .toList();
    }

    @Test
    void aBetterMatchBeatsEverythingAboutTheViewer() {
        assertThat(ranked(
                        candidate("North Base", 2, 6, 40, 1.0),
                        candidate("the north", 0, 0, 0, 1.0),
                        candidate("Northlane", 3, 6, 40, 1.0)))
                .containsExactly("the north", "North Base", "Northlane");
    }

    @Test
    void withinAMatchTheViewerComesBeforeActivityAndActivityBeforeSimilarity() {
        assertThat(ranked(
                        candidate("Busy", 2, 0, 30, 1.0),
                        candidate("Followed", 2, SuggestionService.FOLLOWED, 0, 0.5),
                        candidate("Similar", 2, 0, 0, 0.9),
                        candidate("Near", 2, SuggestionService.NEAR_HOME, 0, 0.5)))
                .containsExactly("Followed", "Near", "Busy", "Similar");
    }

    @Test
    void namesBreakTheLastTieAndTheListIsCapped() {
        assertThat(ranked(candidate("b", 1, 0, 0, 1), candidate("A", 1, 0, 0, 1)))
                .containsExactly("A", "b");
        Candidate[] many = new Candidate[12];
        for (int i = 0; i < many.length; i++) {
            many[i] = candidate("n" + i, 1, 0, i, 1);
        }
        assertThat(SuggestionService.rank(List.of(many), SuggestionService.LIMIT))
                .hasSize(8);
    }
}
