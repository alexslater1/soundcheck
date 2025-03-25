package app.soundcheck.media.internal.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The expected strings were produced by the reference JavaScript implementation (npm {@code blurhash}
 * 2.0.5, {@code encode(pixels, 32, 24, x, y)}) on the same synthetic pixels, on 2025-03-25.
 *
 * <p>One difference between reference implementations matters here: to scale the detail components the
 * JavaScript encoder takes the largest <em>signed</em> value, the C and Swift encoders the largest
 * <em>absolute</em> value. This class follows C/Swift (it does not clip an image whose strongest component
 * is negative). The two agree whenever the strongest component is positive, so the full-string comparisons
 * use an image that darkens to the right and downwards, where it is.
 */
class BlurHashTest {

    static final int W = 32;
    static final int H = 24;

    private interface Pixel {
        int at(int x, int y);
    }

    private static int[] image(Pixel pixel) {
        int[] rgb = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                rgb[y * W + x] = pixel.at(x, y);
            }
        }
        return rgb;
    }

    private static int rgb(int r, int g, int b) {
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** Brightest at the top-left: r = 255 − 8x, g = 250 − 10y, b = 255 − 4(x + y). */
    static final int[] FALLING = image((x, y) -> rgb(255 - x * 8, 250 - y * 10, 255 - (x + y) * 4));

    /** Darkest at the top-left: r = 8x, g = 10y, b = 4(x + y). */
    static final int[] RISING = image((x, y) -> rgb(x * 8, y * 10, (x + y) * 4));

    @Test
    void matchesTheReferenceImplementation() {
        assertThat(BlurHash.encode(FALLING, W, H, 4, 3)).isEqualTo("L.Hx+=}0$8xHqCogjukBgffkfQfk");
        assertThat(BlurHash.encode(FALLING, W, H, 3, 3)).isEqualTo("K.Hx+=}0$8qCogjugffkfQ");
        // A single component is just the average colour.
        assertThat(BlurHash.encode(RISING, W, H, 1, 1)).isEqualTo("00H27b");

        // A flat colour: every detail component is zero.
        int[] flat = new int[16 * 16];
        Arrays.fill(flat, 0xFF4D6D);
        assertThat(BlurHash.encode(flat, 16, 16, 4, 3)).isEqualTo("LKTLyT|{fQ|{|{oLfQoLfQfQfQfQ");
    }

    @Test
    void theAverageColourMatchesTheReferenceEvenWhereTheScalingRuleDiffers() {
        // Reference for RISING at 4x3 is "LDH27b2}|gO,y@NOo2aMhEfkfQfk": same size flag and average colour
        // (characters 1 and 3–6); the scale character and the details differ by the rule described above.
        String hash = BlurHash.encode(RISING, W, H, 4, 3);
        assertThat(hash).hasSize(28).startsWith("L");
        assertThat(hash.substring(2, 6)).isEqualTo("H27b");
    }

    @Test
    void componentCountsAreChecked() {
        assertThatThrownBy(() -> BlurHash.encode(RISING, W, H, 0, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlurHash.encode(RISING, W, H, 4, 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
