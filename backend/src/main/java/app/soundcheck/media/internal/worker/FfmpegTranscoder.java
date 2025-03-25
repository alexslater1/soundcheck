package app.soundcheck.media.internal.worker;

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
import java.io.UncheckedIOException;
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
 * tree"). Output goes to a log file next to the job's files, of which the tail is quoted in errors.
 */
@Component
@ConditionalOnRole(Roles.WORKER)
class FfmpegTranscoder implements Transcoder {

    private static final Duration SHORT = Duration.ofSeconds(60);

    private final JsonMapper json;
    private final Set<Process> running = ConcurrentHashMap.newKeySet();

    FfmpegTranscoder(JsonMapper json) {
        this.json = json;
    }

    @Override
    public ProbeResult probe(Path input) {
        String output;
        try {
            output = run(FfmpegCommands.probe(input), input.resolveSibling("ffprobe.log"), SHORT);
        } catch (TranscodeException e) {
            // ffprobe exits non-zero for files it cannot read at all: not a video, truncated, garbage.
            throw new MediaRejectedException("not a readable media file");
        }
        try {
            return ProbeParser.parse(json.readTree(output));
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
        running.forEach(FfmpegTranscoder::kill);
    }

    private String run(List<String> command, Path log, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
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
            String output = Files.readString(log);
            if (process.exitValue() != 0) {
                throw new TranscodeException(
                        command.getFirst() + " exited with " + process.exitValue() + ": " + tail(output));
            }
            return output;
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new TranscodeException(command.getFirst() + " was interrupted");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            running.remove(process);
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static String tail(String output) {
        String trimmed = output.strip();
        return trimmed.length() <= 500 ? trimmed : "…" + trimmed.substring(trimmed.length() - 500);
    }
}
