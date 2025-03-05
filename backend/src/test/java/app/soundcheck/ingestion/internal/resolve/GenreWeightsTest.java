package app.soundcheck.ingestion.internal.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GenreWeightsTest {

    @Test
    void keepsTheTopThreeAndScalesThemToSumOne() {
        Map<Short, Float> raw = new LinkedHashMap<>();
        raw.put((short) 2, 100f);
        raw.put((short) 3, 50f);
        raw.put((short) 12, 30f);
        raw.put((short) 6, 20f);

        Map<Short, Float> top = GenreWeights.topNormalised(raw, 3);

        assertThat(top.keySet()).containsExactly((short) 2, (short) 3, (short) 12);
        assertThat(top.get((short) 2)).isCloseTo(100f / 180f, within(1e-6f));
        assertThat(top.values().stream().mapToDouble(Float::doubleValue).sum()).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void tiesBreakByGenreIdAndEmptyStaysEmpty() {
        Map<Short, Float> raw = new LinkedHashMap<>();
        raw.put((short) 9, 1f);
        raw.put((short) 4, 1f);
        assertThat(GenreWeights.topNormalised(raw, 1).keySet()).containsExactly((short) 4);
        assertThat(GenreWeights.topNormalised(Map.of(), 3)).isEmpty();
    }
}
