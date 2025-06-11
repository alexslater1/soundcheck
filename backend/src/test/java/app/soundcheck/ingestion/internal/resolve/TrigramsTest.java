package app.soundcheck.ingestion.internal.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Trigram similarity must agree with PostgreSQL's pg_trgm {@code similarity()} on normalised names,
 * because the resolver decides some matches in SQL and some in Java. Expected values were computed by
 * hand from pg_trgm's definition (padded words, |A ∩ B| / |A ∪ B|).
 */
class TrigramsTest {

    @Test
    void identicalNamesAfterNormalisingScoreOne() {
        assertThat(Trigrams.similarity("The Windmill", "windmill")).isEqualTo(1.0);
        assertThat(Trigrams.similarity("Sigur Rós", "SIGUR ROS")).isEqualTo(1.0);
    }

    @Test
    void aOneLetterTypoStaysHighAndDifferentNamesStayLow() {
        // radiohead: 10 trigrams, radiohed: 9, 7 shared ("  r", " ra", rad, adi, dio, ioh, ohe) → 7 / 12
        assertThat(Trigrams.similarity("Radiohead", "Radiohed")).isCloseTo(7.0 / 12, within(1e-9));
        assertThat(Trigrams.similarity("Pale Kites", "Night Bus")).isLessThan(0.1);
    }

    @Test
    void extraWordsLowerTheScore() {
        double score = Trigrams.similarity("Barfly", "London Barfly");
        assertThat(score).isBetween(0.4, 0.7);
        assertThat(Trigrams.similarity("", "")).isZero();
    }

    @Test
    void piecesAreThoseOfPgTrgm() {
        assertThat(Trigrams.trigrams("cat")).containsExactlyInAnyOrder("  c", " ca", "cat", "at ");
    }
}
