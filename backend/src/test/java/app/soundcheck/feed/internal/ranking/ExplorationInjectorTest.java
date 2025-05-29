package app.soundcheck.feed.internal.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.feed.internal.ranking.ExplorationInjector.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** Exploration injection. Items are plain strings here; the key is the string. */
class ExplorationInjectorTest {

    static final List<String> RANKED = numbered("ranked", 40);
    static final List<String> FRESH = numbered("fresh", 10);

    @Test
    void withRateZeroTheRankingIsLeftAsItIs() {
        List<Slot<String>> result = inject(RANKED, FRESH, 0, 20, 7);

        assertThat(items(result)).containsExactlyElementsOf(RANKED.subList(0, 20));
        assertThat(result).noneMatch(Slot::exploration);
    }

    @Test
    void theFirstThreeSlotsAreNeverGivenAwayAndFromTheFourthOnAFreshPostMayTakeTheSlot() {
        // rate 1: every slot from the fourth on explores, until the pool is empty.
        List<Slot<String>> result = inject(RANKED, FRESH, 1, 20, 7);

        assertThat(items(result).subList(0, 3)).containsExactly("ranked-0", "ranked-1", "ranked-2");
        assertThat(result.subList(0, 3)).noneMatch(Slot::exploration);
        assertThat(result.subList(3, 13)).allMatch(Slot::exploration);
        assertThat(items(result.subList(3, 13))).containsExactlyInAnyOrderElementsOf(FRESH);
        // The pool is used up: the ranking continues where it stopped.
        assertThat(items(result.subList(13, 20)))
                .containsExactly("ranked-3", "ranked-4", "ranked-5", "ranked-6", "ranked-7", "ranked-8", "ranked-9");
        assertThat(result.subList(13, 20)).noneMatch(Slot::exploration);
    }

    @Test
    void theSameSeedGivesTheSameListAndAnotherSeedAnotherOne() {
        assertThat(inject(RANKED, FRESH, 0.3, 30, 42)).isEqualTo(inject(RANKED, FRESH, 0.3, 30, 42));
        assertThat(inject(RANKED, FRESH, 0.3, 30, 42)).isNotEqualTo(inject(RANKED, FRESH, 0.3, 30, 43));
    }

    @Test
    void aFreshPostThatIsAlsoRankedAppearsOnce() {
        List<String> ranked = List.of("a", "b", "c", "d", "e", "f");
        List<String> fresh = List.of("e"); // also a ranked candidate, further down

        List<Slot<String>> result = inject(ranked, fresh, 1, 6, 1);

        assertThat(items(result)).containsExactly("a", "b", "c", "e", "d", "f");
        assertThat(result.get(3).exploration()).isTrue();
        assertThat(result.get(4).exploration()).isFalse();
    }

    @Test
    void aboutTheConfiguredShareOfSlotsIsExploration() {
        List<String> ranked = numbered("ranked", 5_000);
        List<String> fresh = numbered("fresh", 5_000);

        List<Slot<String>> result = inject(ranked, fresh, 0.1, 5_000, 2026);

        long explored = result.stream().filter(Slot::exploration).count();
        assertThat(explored).isBetween(400L, 600L); // 10 % of 4,997 eligible slots, give or take
    }

    @Property(tries = 300)
    void whateverTheRateAndSeedTheListHasNoRepeatsAndExploredItemsComeFromThePool(
            @ForAll @DoubleRange(min = 0, max = 1) double rate,
            @ForAll long seed,
            @ForAll @IntRange(min = 0, max = 60) int rankedCount,
            @ForAll @IntRange(min = 0, max = 20) int freshCount,
            @ForAll @IntRange(min = 1, max = 70) int size) {
        List<String> ranked = numbered("ranked", rankedCount);
        // Half of the fresh pool is also in the ranking.
        List<String> fresh = new ArrayList<>(numbered("fresh", freshCount / 2));
        fresh.addAll(ranked.subList(0, Math.min(ranked.size(), freshCount - freshCount / 2)));

        List<Slot<String>> result = inject(ranked, fresh, rate, size, seed);

        assertThat(result.size()).isLessThanOrEqualTo(size);
        assertThat(items(result)).doesNotHaveDuplicates();
        assertThat(result)
                .allMatch(slot -> slot.exploration() ? fresh.contains(slot.item()) : ranked.contains(slot.item()));
        assertThat(result.subList(0, Math.min(3, result.size()))).noneMatch(Slot::exploration);
        // Ranked items keep their relative order.
        List<String> keptRanked = result.stream()
                .filter(slot -> !slot.exploration())
                .map(Slot::item)
                .toList();
        List<String> expectedOrder =
                ranked.stream().filter(keptRanked::contains).toList();
        assertThat(keptRanked).containsExactlyElementsOf(expectedOrder);
    }

    private static List<Slot<String>> inject(
            List<String> ranked, List<String> fresh, double rate, int size, long seed) {
        return ExplorationInjector.inject(ranked, fresh, Function.identity(), rate, size, seed);
    }

    private static List<String> items(List<Slot<String>> slots) {
        return slots.stream().map(Slot::item).toList();
    }

    private static List<String> numbered(String prefix, int n) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            items.add(prefix + "-" + i);
        }
        return items;
    }
}
