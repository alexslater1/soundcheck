package app.soundcheck.media.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.jooq.JSONB;
import org.junit.jupiter.api.Test;

/** The playback DTO says whether the original had sound; only the stored probe knows. */
class MediaAssetProbeTest {

    @Test
    void readsHasAudioFromTheStoredProbe() {
        assertThat(MediaAssetRepository.hasAudio(JSONB.valueOf("{\"hasAudio\":true,\"audioCodec\":\"aac\"}")))
                .isTrue();
        assertThat(MediaAssetRepository.hasAudio(JSONB.valueOf("{\"hasAudio\":false}")))
                .isFalse();
    }

    @Test
    void isUnknownWithoutAProbeOrTheField() {
        assertThat(MediaAssetRepository.hasAudio(null)).isNull();
        assertThat(MediaAssetRepository.hasAudio(JSONB.valueOf("{}"))).isNull();
    }
}
