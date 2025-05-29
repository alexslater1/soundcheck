package app.soundcheck.feed.internal.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.feed.internal.ranking.MmrReranker.Item;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** The diversity re-ranker, with its two required properties. */
class MmrRerankerTest {

    // A small world, so that authors, artists and gigs repeat.
    static final List<UUID> AUTHORS = ids(6);
    static final List<UUID> ARTISTS = ids(5);
    static final List<UUID> GIGS = ids(4);

    @Property(tries = 300)
    void withLambdaOneTheOrderIsThePureScoreOrder(
            @ForAll("candidates") List<Item> candidates, @ForAll @IntRange(min = 1, max = 60) int size) {
        List<Item> expected = new ArrayList<>(candidates);
        expected.sort(Comparator.comparingDouble(Item::score).reversed().thenComparing(Item::postId));

        List<Item> reranked = MmrReranker.rerank(candidates, 1.0, size);

        assertThat(reranked).containsExactlyElementsOf(expected.subList(0, Math.min(size, expected.size())));
    }

    /**
     * With λ below 1, no author appears twice within any three consecutive items, unless at that point
     * every candidate still left was by one of the two authors just shown.
     */
    @Property(tries = 500)
    void withLambdaBelowOneAnAuthorIsNotRepeatedWithinThreeItemsWhileSomeoneElseIsLeft(
            @ForAll("candidates") List<Item> candidates,
            @ForAll @DoubleRange(min = 0, max = 0.99) double lambda,
            @ForAll @IntRange(min = 1, max = 60) int size) {
        List<Item> reranked = MmrReranker.rerank(candidates, lambda, size);

        Set<Item> left = new HashSet<>(candidates);
        for (int i = 0; i < reranked.size(); i++) {
            Item picked = reranked.get(i);
            Set<UUID> justShown = new HashSet<>();
            for (int j = Math.max(0, i - 2); j < i; j++) {
                justShown.add(reranked.get(j).authorId());
            }
            if (justShown.contains(picked.authorId())) {
                assertThat(left)
                        .as("position %d repeats an author although others were left", i)
                        .allMatch(item -> justShown.contains(item.authorId()));
            }
            left.remove(picked);
        }
    }

    @Property(tries = 300)
    void theResultIsASelectionOfTheCandidatesWithoutRepeatsAndOfTheRequestedSize(
            @ForAll("candidates") List<Item> candidates,
            @ForAll @DoubleRange(min = 0, max = 1) double lambda,
            @ForAll @IntRange(min = 1, max = 60) int size) {
        List<Item> reranked = MmrReranker.rerank(candidates, lambda, size);

        assertThat(reranked).hasSize(Math.min(size, candidates.size()));
        assertThat(reranked).doesNotHaveDuplicates();
        assertThat(candidates).containsAll(reranked);
    }

    @Test
    void aSlightlyLowerScoreByAnotherAuthorIsPreferredOverMoreOfTheSame() {
        UUID prolific = AUTHORS.get(0);
        Item first = item(0.90, prolific, Set.of(), null);
        Item second = item(0.89, prolific, Set.of(), null);
        Item third = item(0.88, prolific, Set.of(), null);
        Item other = item(0.60, AUTHORS.get(1), Set.of(), null);
        Item another = item(0.50, AUTHORS.get(2), Set.of(), null);

        List<Item> reranked = MmrReranker.rerank(List.of(first, second, third, other, another), 0.8, 5);

        assertThat(reranked).containsExactly(first, other, another, second, third);
    }

    @Test
    void sharingAnArtistOrAGigCountsAsSimilarButLessThanSharingTheAuthor() {
        Item base = item(0.9, AUTHORS.get(0), Set.of(ARTISTS.get(0)), GIGS.get(0));

        assertThat(MmrReranker.similarity(base, item(0.5, AUTHORS.get(0), Set.of(), null)))
                .isEqualTo(1.0);
        assertThat(MmrReranker.similarity(
                        base, item(0.5, AUTHORS.get(1), Set.of(ARTISTS.get(0), ARTISTS.get(1)), null)))
                .isEqualTo(0.7);
        assertThat(MmrReranker.similarity(base, item(0.5, AUTHORS.get(1), Set.of(ARTISTS.get(1)), GIGS.get(0))))
                .isEqualTo(0.4);
        assertThat(MmrReranker.similarity(base, item(0.5, AUTHORS.get(1), Set.of(ARTISTS.get(1)), GIGS.get(1))))
                .isZero();
        assertThat(MmrReranker.similarity(base, item(0.5, AUTHORS.get(1), Set.of(), null)))
                .isZero();
    }

    @Test
    void aClipOfTheSameArtistIsPushedBehindAnEquallyGoodClipOfAnother() {
        Item top = item(0.9, AUTHORS.get(0), Set.of(ARTISTS.get(0)), null);
        Item sameArtist = item(0.8, AUTHORS.get(1), Set.of(ARTISTS.get(0)), null);
        Item differentArtist = item(0.75, AUTHORS.get(2), Set.of(ARTISTS.get(1)), null);

        assertThat(MmrReranker.rerank(List.of(top, sameArtist, differentArtist), 0.8, 3))
                .containsExactly(top, differentArtist, sameArtist);
        // With the diversity term switched off, plain score order.
        assertThat(MmrReranker.rerank(List.of(top, sameArtist, differentArtist), 1.0, 3))
                .containsExactly(top, sameArtist, differentArtist);
    }

    @Provide
    Arbitrary<List<Item>> candidates() {
        Arbitrary<Item> item = Combinators.combine(
                        Arbitraries.doubles().between(0, 1),
                        Arbitraries.of(AUTHORS),
                        Arbitraries.of(ARTISTS).set().ofMaxSize(2),
                        Arbitraries.of(GIGS).injectNull(0.3))
                .as((score, author, artists, gig) -> new Item(UUID.randomUUID(), score, author, artists, gig));
        return item.list().ofMinSize(1).ofMaxSize(80);
    }

    private static Item item(double score, UUID author, Set<UUID> artists, UUID gig) {
        return new Item(UUID.randomUUID(), score, author, artists, gig);
    }

    private static List<UUID> ids(int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }
}
