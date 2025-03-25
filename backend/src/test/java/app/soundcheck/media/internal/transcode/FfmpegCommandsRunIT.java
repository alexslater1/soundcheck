package app.soundcheck.media.internal.transcode;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.shared.config.JsonMappers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the built commands through the real ffprobe and ffmpeg (they must be on the PATH: installed
 * locally per SETUP.md and by an apt step in CI). The golden lists prove the commands are what was
 * intended; this proves ffmpeg accepts them and produces a playable ladder.
 */
class FfmpegCommandsRunIT {

    @TempDir
    Path dir;

    @Test
    void aPortraitClipWithoutAudioBecomesAnHlsLadderWithPosterAndThumb() throws Exception {
        // 3 s of a test pattern, 720×1280, no audio track.
        Path input = dir.resolve("original");
        run(List.of(
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "error",
                "-nostdin",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=duration=3:size=720x1280:rate=30",
                "-c:v",
                "libx264",
                "-pix_fmt",
                "yuv420p",
                "-f",
                "mp4",
                input.toString()));

        ProbeResult probe = ProbeParser.parse(JsonMappers.builder().build().readTree(run(FfmpegCommands.probe(input))));
        assertThat(probe.displayWidth()).isEqualTo(720);
        assertThat(probe.displayHeight()).isEqualTo(1280);
        assertThat(probe.hasAudio()).isFalse();
        assertThat(probe.durationMs()).isBetween(2_900L, 3_100L);

        Path out = Files.createDirectories(dir.resolve("out"));
        List<Rung.Sized> ladder = Rung.ladderFor(probe);
        assertThat(ladder).hasSize(3);
        run(quiet(FfmpegCommands.transcode(input, out, probe, ladder)));
        run(quiet(FfmpegCommands.poster(input, out.resolve("poster.jpg"), probe)));
        run(quiet(FfmpegCommands.thumb(input, out.resolve("thumb.webp"), probe)));

        String master = Files.readString(out.resolve("master.m3u8"));
        assertThat(master)
                .contains("RESOLUTION=360x640", "RESOLUTION=540x960", "RESOLUTION=720x1280")
                .contains("360p/index.m3u8", "540p/index.m3u8", "720p/index.m3u8")
                .contains("mp4a.40.2"); // every variant has (silent) AAC audio
        for (String rung : List.of("360p", "540p", "720p")) {
            String playlist = Files.readString(out.resolve(rung).resolve("index.m3u8"));
            assertThat(playlist)
                    .contains("#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-MAP:URI=", "seg_000.m4s", "#EXT-X-ENDLIST");
            assertThat(out.resolve(rung).resolve("seg_000.m4s")).isNotEmptyFile();
        }
        assertThat(out.resolve("poster.jpg")).isNotEmptyFile();
        assertThat(out.resolve("thumb.webp")).isNotEmptyFile();

        // The 720p rung really is 720×1280 with H.264 video and AAC audio.
        ProbeResult rendition = ProbeParser.parse(JsonMappers.builder()
                .build()
                .readTree(run(FfmpegCommands.probe(out.resolve("720p").resolve("index.m3u8")))));
        assertThat(rendition.width()).isEqualTo(720);
        assertThat(rendition.height()).isEqualTo(1280);
        assertThat(rendition.videoCodec()).isEqualTo("h264");
        assertThat(rendition.audioCodec()).isEqualTo("aac");
    }

    private static List<String> quiet(List<String> command) {
        List<String> quiet = new ArrayList<>(command);
        quiet.addAll(1, List.of("-loglevel", "error"));
        return quiet;
    }

    private String run(List<String> command) throws IOException, InterruptedException {
        Path log = Files.createTempFile(dir, "run", ".log");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        assertThat(process.waitFor(120, TimeUnit.SECONDS))
                .as("finished in time: " + command.getFirst())
                .isTrue();
        String output = Files.readString(log);
        assertThat(process.exitValue()).as(output).isZero();
        return output;
    }
}
