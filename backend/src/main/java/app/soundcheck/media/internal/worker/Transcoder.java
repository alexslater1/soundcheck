package app.soundcheck.media.internal.worker;

import app.soundcheck.media.internal.transcode.ProbeResult;
import app.soundcheck.media.internal.transcode.Rung;
import java.nio.file.Path;
import java.util.List;

/**
 * Transcoder port: what the worker needs from FFmpeg, as an
 * interface, so the worker's control flow can be tested with a transcoder that fails on demand.
 */
public interface Transcoder {

    /**
     * @throws app.soundcheck.media.internal.transcode.MediaRejectedException when the file is not a usable video
     * @throws TranscodeException when ffprobe itself could not run (missing, timed out, killed): not a
     *     verdict on the file
     */
    ProbeResult probe(Path input);

    /** Writes {@code master.m3u8} and one folder per rung under {@code outputDir}. */
    void transcode(Path input, Path outputDir, ProbeResult probe, List<Rung.Sized> ladder);

    void poster(Path input, Path output, ProbeResult probe);

    void thumb(Path input, Path output, ProbeResult probe);

    /** Kills whatever process is running for the current thread's job (graceful shutdown). */
    void abortRunning();

    /**
     * The tool could not do its job: it could not be started, ran longer than allowed, was killed, or
     * exited with an error. Retried like any other failure.
     */
    class TranscodeException extends RuntimeException {
        public TranscodeException(String message) {
            super(message);
        }
    }

    /**
     * The tool ran to the end by itself and said no (a non-zero exit code). For ffprobe that is a verdict
     * on the file; a tool that never ran or was killed says nothing about the file.
     */
    class ToolRefusedException extends TranscodeException {
        public ToolRefusedException(String message) {
            super(message);
        }
    }
}
