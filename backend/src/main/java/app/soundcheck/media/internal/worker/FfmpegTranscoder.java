package app.soundcheck.media.internal.worker;

import app.soundcheck.media.internal.MediaProperties;
import app.soundcheck.media.internal.transcode.FfmpegCommands;
import app.soundcheck.media.internal.transcode.MediaRejectedException;
import app.soundcheck.media.internal.transcode.ProbeParser;
import app.soundcheck.media.internal.transcode.ProbeResult;
import app.soundcheck.media.internal.transcode.Rung;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Runs ffprobe and ffmpeg as child processes. Arguments are passed as a list (no shell). Every process
 * has a deadline; when it passes, the process and its children are killed.
 * Output goes to files next to the job's files; the tail of the error log is quoted in errors.
 */
@Component
@ConditionalOnRole(Roles.WORKER)
class FfmpegTranscoder implements Transcoder {

    private static final Duration SHORT = Duration.ofSeconds(60);

    private final JsonMapper json;
    private final MediaProperties media;
    private static final int TAIL_BYTES = 800;

    private final Set<Process> running = ConcurrentHashMap.newKeySet();
    private final Set<Process> aborted = ConcurrentHashMap.newKeySet();

    FfmpegTranscoder(JsonMapper json, MediaProperties media) {
        this.json = json;
        this.media = media;
    }

    @Override
    public ProbeResult probe(Path input) {
        String output;
        try {
            output = run(FfmpegCommands.probe(input), input.resolveSibling("ffprobe.log"), SHORT);
        } catch (ToolRefusedException e) {
            // ffprobe ran and exited non-zero: the file cannot be read as one of the accepted containers
            // (not a video, truncated, garbage, or a playlist dressed up as one). That is final. Any other
            // failure (ffprobe missing, timed out, killed on shutdown) propagates and is retried.
            throw new MediaRejectedException("not a readable media file");
        }
        try {
            return ProbeParser.parse(json.readTree(output), media.maxVideoDuration());
        } catch (JacksonException e) {
            throw new MediaRejectedException("not a readable media file");
        }
    }

    @Override
    public void transcode(Path input, Path outputDir, ProbeResult probe, List<Rung.Sized> ladder) {
        run(
                FfmpegCommands.transcode(input, outputDir, probe, ladder),
                input.resolveSibling("ffmpeg.log"),
                FfmpegCommands.timeout(probe));
    }

    @Override
    public void poster(Path input, Path output, ProbeResult probe) {
        run(FfmpegCommands.poster(input, output, probe), input.resolveSibling("poster.log"), SHORT);
    }

    @Override
    public void thumb(Path input, Path output, ProbeResult probe) {
        run(FfmpegCommands.thumb(input, output, probe), input.resolveSibling("thumb.log"), SHORT);
    }

    @Override
    public void abortRunning() {
        for (Process process : running) {
            // Remembered, so that the exit code of a killed process is not mistaken for the tool's verdict.
            aborted.add(process);
            kill(process);
        }
    }

    /**
     * Runs one process to the end. Standard output goes to one file and is returned; standard error goes
     * to {@code log}, of which only the tail is read, and only when the process failed. Both are decoded
     * leniently: media files carry tags in any encoding, and a stray byte must not fail a good video.
     */
    String run(List<String> command, Path log, Duration timeout) {
        Path out = log.resolveSibling(log.getFileName() + ".out");
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(out.toFile())
                    .redirectError(log.toFile())
                    .start();
        } catch (IOException e) {
            throw new TranscodeException(command.getFirst() + " could not be started: " + e.getMessage());
        }
        running.add(process);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                throw new TranscodeException(command.getFirst() + " timed out after " + timeout.toSeconds() + " s");
            }
            if (aborted.remove(process)) {
                throw new TranscodeException(command.getFirst() + " was stopped");
            }
            if (process.exitValue() != 0) {
                throw new ToolRefusedException(
                        command.getFirst() + " exited with " + process.exitValue() + ": " + tail(log));
            }
            return new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new TranscodeException(command.getFirst() + " was interrupted");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            running.remove(process);
            aborted.remove(process);
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** The last {@value #TAIL_BYTES} bytes of a log file, whatever its size and encoding. */
    static String tail(Path log) {
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long start = Math.max(0, file.length() - TAIL_BYTES);
            byte[] bytes = new byte[(int) (file.length() - start)];
            file.seek(start);
            file.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8).strip();
            return start > 0 ? "…" + text : text;
        } catch (IOException e) {
            return "(log not readable)";
        }
    }
}
