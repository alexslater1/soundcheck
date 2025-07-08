package app.soundcheck.media.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** The refusals name the configured limits in words. */
class MediaLimitsTest {

    @Test
    void aClipLengthIsInMinutesWhenItIsWholeMinutesPlusTheSecondOfTolerance() {
        assertThat(MediaLimits.clipLength(Duration.ofSeconds(181))).isEqualTo("3 minutes");
        assertThat(MediaLimits.clipLength(Duration.ofMinutes(5))).isEqualTo("5 minutes");
        assertThat(MediaLimits.clipLength(Duration.ofSeconds(61))).isEqualTo("60 seconds");
        assertThat(MediaLimits.clipLength(Duration.ofSeconds(90))).isEqualTo("90 seconds");
    }

    @Test
    void aSizeIsInGigabytesWhenItIsWholeGigabytes() {
        assertThat(MediaLimits.videoSize(DataSize.ofGigabytes(1))).isEqualTo("1 GB");
        assertThat(MediaLimits.videoSize(DataSize.ofMegabytes(500))).isEqualTo("500 MB");
        assertThat(MediaLimits.videoSize(DataSize.ofMegabytes(20))).isEqualTo("20 MB");
    }
}
