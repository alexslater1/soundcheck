package app.soundcheck.media.internal.transcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden argument lists. Each test fixes the complete ffmpeg command for one kind of
 * source, so any change to the ladder, the encoder settings or the HLS options shows up as a diff here.
 * {@code FfmpegCommandsRunIT} runs the same commands through the real ffmpeg.
 */
class FfmpegCommandsTest {

    static final Path IN = Path.of("/tmp/soundcheck/m1/original");
    static final Path OUT = Path.of("/tmp/soundcheck/m1/out");

    static ProbeResult probe(int width, int height, int rotation, boolean audio) {
        return new ProbeResult(width, height, rotation, 20_000, "h264", audio, audio ? "aac" : null, 30.0);
    }

    @Test
    void landscape1080pGetsAllThreeRungs() {
        ProbeResult probe = probe(1920, 1080, 0, true);
        List<Rung.Sized> ladder = Rung.ladderFor(probe);

        assertThat(ladder)
                .containsExactly(
                        new Rung.Sized(Rung.P360, 640, 360),
                        new Rung.Sized(Rung.P540, 960, 540),
                        new Rung.Sized(Rung.P720, 1280, 720));
        assertThat(FfmpegCommands.transcode(IN, OUT, probe, ladder))
                .containsExactly(
                        "ffmpeg",
                        "-hide_banner",
                        "-nostdin",
                        "-y",
                        "-i",
                        "/tmp/soundcheck/m1/original",
                        "-filter_complex",
                        "[0:v]split=3[v0][v1][v2];"
                                + "[v0]scale=w=640:h=360,setsar=1[v0o];"
                                + "[v1]scale=w=960:h=540,setsar=1[v1o];"
                                + "[v2]scale=w=1280:h=720,setsar=1[v2o]",
                        "-map",
                        "[v0o]",
                        "-map",
                        "0:a:0",
                        "-map",
                        "[v1o]",
                        "-map",
                        "0:a:0",
                        "-map",
                        "[v2o]",
                        "-map",
                        "0:a:0",
                        "-c:v",
                        "libx264",
                        "-preset",
                        "veryfast",
                        "-pix_fmt",
                        "yuv420p",
                        "-sc_threshold",
                        "0",
                        "-force_key_frames",
                        "expr:gte(t,n_forced*2)",
                        "-profile:v:0",
                        "main",
                        "-b:v:0",
                        "800k",
                        "-maxrate:v:0",
                        "856k",
                        "-bufsize:v:0",
                        "1200k",
                        "-profile:v:1",
                        "high",
                        "-b:v:1",
                        "1600k",
                        "-maxrate:v:1",
                        "1712k",
                        "-bufsize:v:1",
                        "2400k",
                        "-profile:v:2",
                        "high",
                        "-b:v:2",
                        "3000k",
                        "-maxrate:v:2",
                        "3210k",
                        "-bufsize:v:2",
                        "4500k",
                        "-c:a",
                        "aac",
                        "-b:a",
                        "128k",
                        "-ac",
                        "2",
                        "-ar",
                        "48000",
                        "-f",
                        "hls",
                        "-hls_time",
                        "4",
                        "-hls_playlist_type",
                        "vod",
                        "-hls_segment_type",
                        "fmp4",
                        "-hls_flags",
                        "independent_segments",
                        "-hls_fmp4_init_filename",
                        "init.mp4",
                        "-master_pl_name",
                        "master.m3u8",
                        "-var_stream_map",
                        "v:0,a:0,name:360p v:1,a:1,name:540p v:2,a:2,name:720p",
                        "-hls_segment_filename",
                        "/tmp/soundcheck/m1/out/%v/seg_%03d.m4s",
                        "/tmp/soundcheck/m1/out/%v/index.m3u8");
    }

    @Test
    void portrait720pRecordedSidewaysIsScaledOnItsDisplayedShape() {
        // A phone's portrait clip: 1280×720 pixels with a 90° rotation → shown as 720×1280.
        ProbeResult probe = probe(1280, 720, 90, true);
        List<Rung.Sized> ladder = Rung.ladderFor(probe);

        assertThat(probe.displayWidth()).isEqualTo(720);
        assertThat(probe.displayHeight()).isEqualTo(1280);
        assertThat(ladder)
                .containsExactly(
                        new Rung.Sized(Rung.P360, 360, 640),
                        new Rung.Sized(Rung.P540, 540, 960),
                        new Rung.Sized(Rung.P720, 720, 1280));
        List<String> args = FfmpegCommands.transcode(IN, OUT, probe, ladder);
        assertThat(args.get(args.indexOf("-filter_complex") + 1))
                .isEqualTo("[0:v]split=3[v0][v1][v2];"
                        + "[v0]scale=w=360:h=640,setsar=1[v0o];"
                        + "[v1]scale=w=540:h=960,setsar=1[v1o];"
                        + "[v2]scale=w=720:h=1280,setsar=1[v2o]");
        assertThat(args).containsSequence("-var_stream_map", "v:0,a:0,name:360p v:1,a:1,name:540p v:2,a:2,name:720p");
    }

    @Test
    void a480pSourceGetsOnlyTheLowestRungBecauseNothingIsUpscaled() {
        ProbeResult probe = probe(854, 480, 0, true);
        List<Rung.Sized> ladder = Rung.ladderFor(probe);

        assertThat(ladder).containsExactly(new Rung.Sized(Rung.P360, 640, 360));
        assertThat(FfmpegCommands.transcode(IN, OUT, probe, ladder))
                .containsExactly(
                        "ffmpeg",
                        "-hide_banner",
                        "-nostdin",
                        "-y",
                        "-i",
                        "/tmp/soundcheck/m1/original",
                        "-filter_complex",
                        "[0:v]split=1[v0];[v0]scale=w=640:h=360,setsar=1[v0o]",
                        "-map",
                        "[v0o]",
                        "-map",
                        "0:a:0",
                        "-c:v",
                        "libx264",
                        "-preset",
                        "veryfast",
                        "-pix_fmt",
                        "yuv420p",
                        "-sc_threshold",
                        "0",
                        "-force_key_frames",
                        "expr:gte(t,n_forced*2)",
                        "-profile:v:0",
                        "main",
                        "-b:v:0",
                        "800k",
                        "-maxrate:v:0",
                        "856k",
                        "-bufsize:v:0",
                        "1200k",
                        "-c:a",
                        "aac",
                        "-b:a",
                        "128k",
                        "-ac",
                        "2",
                        "-ar",
                        "48000",
                        "-f",
                        "hls",
                        "-hls_time",
                        "4",
                        "-hls_playlist_type",
                        "vod",
                        "-hls_segment_type",
                        "fmp4",
                        "-hls_flags",
                        "independent_segments",
                        "-hls_fmp4_init_filename",
                        "init.mp4",
                        "-master_pl_name",
                        "master.m3u8",
                        "-var_stream_map",
                        "v:0,a:0,name:360p",
                        "-hls_segment_filename",
                        "/tmp/soundcheck/m1/out/%v/seg_%03d.m4s",
                        "/tmp/soundcheck/m1/out/%v/index.m3u8");
    }

    @Test
    void aSourceWithoutAudioGetsASilentTrackCutToTheVideosLength() {
        ProbeResult probe = probe(960, 540, 0, false);
        List<Rung.Sized> ladder = Rung.ladderFor(probe);

        assertThat(ladder).extracting(Rung.Sized::rung).containsExactly(Rung.P360, Rung.P540);
        List<String> args = FfmpegCommands.transcode(IN, OUT, probe, ladder);
        assertThat(args)
                .containsSequence(
                        "-i",
                        "/tmp/soundcheck/m1/original",
                        "-f",
                        "lavfi",
                        "-i",
                        "anullsrc=channel_layout=stereo:sample_rate=48000",
                        "-filter_complex");
        // Audio comes from the second input (the generated silence), once per rung.
        assertThat(args).containsSequence("-map", "[v0o]", "-map", "1:a:0", "-map", "[v1o]", "-map", "1:a:0");
        assertThat(args).doesNotContain("0:a:0");
        assertThat(args).containsSequence("-ar", "48000", "-shortest", "-f", "hls");
    }

    @Test
    void aTinyOrOddlyShapedSourceKeepsItsSizeWithEvenDimensions() {
        // Smaller than the lowest rung: kept at its own size, not blown up to 640.
        assertThat(Rung.ladderFor(probe(320, 240, 0, true))).containsExactly(new Rung.Sized(Rung.P360, 320, 240));
        // Square, and a 4:3 clip whose scaled height would be odd: both sides come out even.
        assertThat(Rung.ladderFor(probe(1080, 1080, 0, true)))
                .containsExactly(new Rung.Sized(Rung.P360, 640, 640), new Rung.Sized(Rung.P540, 960, 960));
        assertThat(Rung.ladderFor(probe(1000, 562, 0, true)))
                .containsExactly(new Rung.Sized(Rung.P360, 640, 360), new Rung.Sized(Rung.P540, 960, 540));
        assertThat(Rung.ladderFor(probe(333, 251, 180, true))).containsExactly(new Rung.Sized(Rung.P360, 332, 250));
    }

    @Test
    void posterThumbProbeAndTimeout() {
        ProbeResult landscape = probe(1920, 1080, 0, true);
        assertThat(FfmpegCommands.poster(IN, OUT.resolve("poster.jpg"), landscape))
                .containsExactly(
                        "ffmpeg",
                        "-hide_banner",
                        "-nostdin",
                        "-y",
                        "-ss",
                        "1.000",
                        "-i",
                        "/tmp/soundcheck/m1/original",
                        "-frames:v",
                        "1",
                        "-vf",
                        "scale=w='min(720,iw)':h=-2",
                        "-q:v",
                        "4",
                        "/tmp/soundcheck/m1/out/poster.jpg");
        ProbeResult shortPortrait = new ProbeResult(720, 1280, 0, 1_200, "h264", true, "aac", 30.0);
        // A 1.2 s clip: the frame is taken at half its duration; portrait scales by height.
        assertThat(FfmpegCommands.thumb(IN, OUT.resolve("thumb.webp"), shortPortrait))
                .containsExactly(
                        "ffmpeg",
                        "-hide_banner",
                        "-nostdin",
                        "-y",
                        "-ss",
                        "0.600",
                        "-i",
                        "/tmp/soundcheck/m1/original",
                        "-frames:v",
                        "1",
                        "-vf",
                        "scale=w=-2:h='min(320,ih)'",
                        "-c:v",
                        "libwebp",
                        "-quality",
                        "75",
                        "/tmp/soundcheck/m1/out/thumb.webp");
        assertThat(FfmpegCommands.probe(IN))
                .containsExactly(
                        "ffprobe",
                        "-v",
                        "error",
                        "-print_format",
                        "json",
                        "-show_format",
                        "-show_streams",
                        "/tmp/soundcheck/m1/original");
        // 4 × 20 s + 60 s.
        assertThat(FfmpegCommands.timeout(landscape)).isEqualTo(Duration.ofSeconds(140));
    }
}
