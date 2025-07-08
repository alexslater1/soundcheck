package app.soundcheck.media.internal.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** ffprobe JSON → {@link ProbeResult}, and the rejection rules. */
class ProbeParserTest {

    static final JsonMapper JSON = JsonMappers.builder().build();
    /** 3 minutes plus a second of tolerance (the {@code max-video-duration} default). */
    static final Duration LIMIT = Duration.ofSeconds(181);

    private static ProbeResult parse(JsonNode probe) {
        return ProbeParser.parse(probe, LIMIT);
    }

    private static JsonNode probe(String streams, String duration) {
        try {
            return JSON.readTree("{\"streams\":[" + streams + "],\"format\":{\"duration\":\"" + duration + "\"}}");
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    static final String H264_1080 =
            "{\"codec_type\":\"video\",\"codec_name\":\"h264\",\"width\":1920,\"height\":1080,\"avg_frame_rate\":\"30000/1001\"}";
    static final String AAC = "{\"codec_type\":\"audio\",\"codec_name\":\"aac\"}";

    @Test
    void parsesAnOrdinaryClip() {
        ProbeResult result = parse(probe(H264_1080 + "," + AAC, "19.986000"));

        assertThat(result.width()).isEqualTo(1920);
        assertThat(result.height()).isEqualTo(1080);
        assertThat(result.rotation()).isZero();
        assertThat(result.durationMs()).isEqualTo(19_986);
        assertThat(result.videoCodec()).isEqualTo("h264");
        assertThat(result.hasAudio()).isTrue();
        assertThat(result.audioCodec()).isEqualTo("aac");
        assertThat(result.frameRate()).isCloseTo(29.97, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void readsRotationFromTheDisplayMatrixOrTheOlderRotateTag() {
        // Android writes a display matrix; ffprobe reports it counter-clockwise (-90 = 90° clockwise).
        String matrix = "{\"codec_type\":\"video\",\"codec_name\":\"hevc\",\"width\":1280,\"height\":720,"
                + "\"side_data_list\":[{\"side_data_type\":\"Display Matrix\",\"rotation\":-90}]}";
        ProbeResult sideways = parse(probe(matrix, "8.0"));
        assertThat(sideways.rotation()).isEqualTo(90);
        assertThat(sideways.displayWidth()).isEqualTo(720);
        assertThat(sideways.displayHeight()).isEqualTo(1280);
        assertThat(sideways.hasAudio()).isFalse();

        String tag =
                "{\"codec_type\":\"video\",\"codec_name\":\"h264\",\"width\":1280,\"height\":720,\"tags\":{\"rotate\":\"270\"}}";
        assertThat(parse(probe(tag, "8.0")).rotation()).isEqualTo(270);
        String upsideDown = "{\"codec_type\":\"video\",\"codec_name\":\"h264\",\"width\":1280,\"height\":720,"
                + "\"side_data_list\":[{\"rotation\":180}]}";
        ProbeResult flipped = parse(probe(upsideDown, "8.0"));
        assertThat(flipped.rotation()).isEqualTo(180);
        assertThat(flipped.displayWidth()).isEqualTo(1280);
    }

    @Test
    void takesTheDurationFromTheStreamWhenTheContainerHasNone() throws Exception {
        String video =
                "{\"codec_type\":\"video\",\"codec_name\":\"vp9\",\"width\":640,\"height\":360,\"duration\":\"12.5\"}";
        JsonNode json = JSON.readTree("{\"streams\":[" + video + "],\"format\":{}}");
        assertThat(parse(json).durationMs()).isEqualTo(12_500);
    }

    @Test
    void rejectsWhatMustNotBeTranscoded() throws Exception {
        // An audio file (even one with cover art, which ffprobe lists as a video stream).
        String cover = "{\"codec_type\":\"video\",\"codec_name\":\"mjpeg\",\"width\":600,\"height\":600,"
                + "\"disposition\":{\"attached_pic\":1}}";
        assertRejected(probe(AAC, "30.0"), "no video stream");
        assertRejected(probe(cover + "," + AAC, "30.0"), "no video stream");
        // Longer than 3 minutes (181 s with the second of tolerance), or than whatever is configured.
        assertRejected(probe(H264_1080, "181.5"), "longer than 3 minutes (181.5 s)");
        assertThat(parse(probe(H264_1080, "180.9")).durationMs()).isEqualTo(180_900);
        assertThatThrownBy(() -> ProbeParser.parse(probe(H264_1080, "61.5"), Duration.ofSeconds(61)))
                .hasMessageContaining("longer than 60 seconds");
        // Too large in either direction.
        String huge = "{\"codec_type\":\"video\",\"codec_name\":\"h264\",\"width\":7680,\"height\":4320}";
        assertRejected(probe(huge, "10.0"), "larger than 4096");
        String tall = "{\"codec_type\":\"video\",\"codec_name\":\"h264\",\"width\":2160,\"height\":4097}";
        assertRejected(probe(tall, "10.0"), "larger than 4096");
        // A stream ffprobe could not identify, or one without a size or a duration.
        String unknown = "{\"codec_type\":\"video\",\"codec_name\":\"unknown\",\"width\":640,\"height\":360}";
        assertRejected(probe(unknown, "10.0"), "codec not recognised");
        String noSize = "{\"codec_type\":\"video\",\"codec_name\":\"h264\"}";
        assertRejected(probe(noSize, "10.0"), "no dimensions");
        assertRejected(JSON.readTree("{\"streams\":[" + H264_1080 + "],\"format\":{}}"), "duration unknown");
        // Not a media file at all: ffprobe prints an empty object.
        assertRejected(JSON.readTree("{}"), "no video stream");
    }

    private static void assertRejected(JsonNode probe, String reason) {
        assertThatThrownBy(() -> parse(probe))
                .isInstanceOf(MediaRejectedException.class)
                .hasMessageContaining(reason);
    }
}
