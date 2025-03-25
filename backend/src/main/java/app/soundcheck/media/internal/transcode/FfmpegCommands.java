package app.soundcheck.media.internal.transcode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Builds the argument lists the worker runs. Pure: given the probe, the commands are fully
 * determined, which is what the golden-list tests pin down. Arguments are passed to the process as a
 * list, never through a shell, so paths are not subject to quoting or injection.
 */
public final class FfmpegCommands {

    static final int SEGMENT_SECONDS = 4;
    static final int KEYFRAME_SECONDS = 2;
    static final int POSTER_LONG_EDGE = 720;
    static final int THUMB_LONG_EDGE = 320;

    private FfmpegCommands() {}

    /** {@code ffprobe} invocation whose JSON {@link ProbeParser} reads. */
    public static List<String> probe(Path input) {
        return List.of(
                "ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams", input.toString());
    }

    /**
     * One ffmpeg run that produces every rung: the decoded video is split, each copy scaled and encoded
     * as H.264, audio as AAC, and the result written as HLS with fMP4 (CMAF) segments, one folder per
     * rung plus {@code master.m3u8}. ffmpeg applies the rotation itself, so the scale sizes are the
     * displayed ones.
     */
    public static List<String> transcode(Path input, Path outputDir, ProbeResult probe, List<Rung.Sized> ladder) {
        List<String> args =
                new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", input.toString()));
        if (!probe.hasAudio()) {
            // Players expect an audio track in every variant: add silence, cut to the video's length.
            args.addAll(List.of("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000"));
        }

        int n = ladder.size();
        StringBuilder filter = new StringBuilder("[0:v]split=").append(n);
        for (int i = 0; i < n; i++) {
            filter.append("[v").append(i).append("]");
        }
        for (int i = 0; i < n; i++) {
            Rung.Sized sized = ladder.get(i);
            filter.append(";[v")
                    .append(i)
                    .append("]scale=w=")
                    .append(sized.width())
                    .append(":h=")
                    .append(sized.height())
                    .append(",setsar=1[v")
                    .append(i)
                    .append("o]");
        }
        args.addAll(List.of("-filter_complex", filter.toString()));

        String audioInput = probe.hasAudio() ? "0:a:0" : "1:a:0";
        for (int i = 0; i < n; i++) {
            args.addAll(List.of("-map", "[v" + i + "o]", "-map", audioInput));
        }

        args.addAll(List.of(
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-pix_fmt", "yuv420p",
                "-sc_threshold", "0",
                "-force_key_frames", "expr:gte(t,n_forced*" + KEYFRAME_SECONDS + ")"));
        for (int i = 0; i < n; i++) {
            Rung rung = ladder.get(i).rung();
            args.addAll(List.of(
                    "-profile:v:" + i, rung.profile,
                    "-b:v:" + i, rung.bitrateK + "k",
                    "-maxrate:v:" + i, rung.maxrateK + "k",
                    "-bufsize:v:" + i, rung.bufsizeK + "k"));
        }
        args.addAll(List.of("-c:a", "aac", "-b:a", "128k", "-ac", "2", "-ar", "48000"));
        if (!probe.hasAudio()) {
            args.add("-shortest");
        }

        StringJoiner variants = new StringJoiner(" ");
        for (int i = 0; i < n; i++) {
            variants.add("v:" + i + ",a:" + i + ",name:" + ladder.get(i).rung().label);
        }
        args.addAll(List.of(
                "-f",
                "hls",
                "-hls_time",
                String.valueOf(SEGMENT_SECONDS),
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
                variants.toString(),
                "-hls_segment_filename",
                outputDir.resolve("%v").resolve("seg_%03d.m4s").toString(),
                outputDir.resolve("%v").resolve("index.m3u8").toString()));
        return args;
    }

    /** One frame at min(1 s, half the duration) as a JPEG whose longer side is at most 720 px. */
    public static List<String> poster(Path input, Path output, ProbeResult probe) {
        return still(input, output, probe, POSTER_LONG_EDGE, List.of("-q:v", "4"));
    }

    /** The same frame as a WebP whose longer side is at most 320 px. */
    public static List<String> thumb(Path input, Path output, ProbeResult probe) {
        return still(input, output, probe, THUMB_LONG_EDGE, List.of("-c:v", "libwebp", "-quality", "75"));
    }

    /** A transcode may take 4 × the clip's duration plus a minute, then it is killed. */
    public static Duration timeout(ProbeResult probe) {
        return Duration.ofMillis(4 * probe.durationMs()).plusSeconds(60);
    }

    private static List<String> still(Path input, Path output, ProbeResult probe, int longEdge, List<String> codec) {
        double seconds = Math.min(1.0, probe.durationMs() / 2000.0);
        // -2 keeps the aspect ratio with an even size; min() never upscales a small source.
        String scale = probe.displayWidth() >= probe.displayHeight()
                ? "scale=w='min(" + longEdge + ",iw)':h=-2"
                : "scale=w=-2:h='min(" + longEdge + ",ih)'";
        List<String> args = new ArrayList<>(List.of(
                "ffmpeg",
                "-hide_banner",
                "-nostdin",
                "-y",
                "-ss",
                String.format(Locale.ROOT, "%.3f", seconds),
                "-i",
                input.toString(),
                "-frames:v",
                "1",
                "-vf",
                scale));
        args.addAll(codec);
        args.add(output.toString());
        return args;
    }
}
