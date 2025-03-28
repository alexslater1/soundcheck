package app.soundcheck.media.internal.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import app.soundcheck.media.internal.transcode.MediaRejectedException;
import app.soundcheck.media.internal.worker.Transcoder.ToolRefusedException;
import app.soundcheck.media.internal.worker.Transcoder.TranscodeException;
import app.soundcheck.shared.config.JsonMappers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The process runner under the transcoder, with ordinary shell commands standing in for ffmpeg: what
 * counts as the tool's own verdict (a non-zero exit), what does not (missing, timed out, killed), that a
 * timeout kills the whole process tree, and that odd bytes in the output never fail a run.
 */
class FfmpegTranscoderTest {

    @TempDir
    Path dir;

    final FfmpegTranscoder transcoder =
            new FfmpegTranscoder(JsonMappers.builder().build());

    @Test
    void aNonZeroExitIsTheToolsVerdictAndQuotesTheEndOfItsErrorOutput() {
        Throwable thrown = catchThrowable(() -> transcoder.run(
                List.of("sh", "-c", "echo 'moov atom not found' >&2; exit 3"),
                dir.resolve("a.log"),
                Duration.ofSeconds(10)));

        assertThat(thrown).isInstanceOf(ToolRefusedException.class);
        assertThat(thrown).hasMessageContaining("exited with 3").hasMessageContaining("moov atom not found");
    }

    @Test
    void aToolThatCannotBeStartedIsNotAVerdict() {
        Throwable thrown = catchThrowable(() ->
                transcoder.run(List.of("no-such-binary-soundcheck"), dir.resolve("b.log"), Duration.ofSeconds(10)));

        assertThat(thrown).isInstanceOf(TranscodeException.class).isNotInstanceOf(ToolRefusedException.class);
        assertThat(thrown).hasMessageContaining("could not be started");
    }

    @Test
    void aTimeoutKillsTheProcessAndItsChildrenAndIsNotAVerdict() throws Exception {
        Path pidFile = dir.resolve("child.pid");
        // The shell starts a child and waits for it: both must be gone after the timeout.
        Throwable thrown = catchThrowable(() -> transcoder.run(
                List.of("sh", "-c", "sleep 60 & echo $! > " + pidFile + "; wait"),
                dir.resolve("c.log"),
                Duration.ofMillis(400)));

        assertThat(thrown).isInstanceOf(TranscodeException.class).isNotInstanceOf(ToolRefusedException.class);
        assertThat(thrown).hasMessageContaining("timed out");
        long childPid = Long.parseLong(Files.readString(pidFile).strip());
        // destroyForcibly is asynchronous: give the kernel a moment.
        for (int i = 0;
                i < 50 && ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false);
                i++) {
            Thread.sleep(50);
        }
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
                .as("the child process is still alive")
                .isFalse();
    }

    @Test
    void aProcessKilledOnShutdownIsNotAVerdictEither() throws Exception {
        CompletableFuture<Throwable> result = CompletableFuture.supplyAsync(() -> catchThrowable(
                () -> transcoder.run(List.of("sleep", "60"), dir.resolve("d.log"), Duration.ofSeconds(30))));
        Thread.sleep(300); // let it start

        transcoder.abortRunning();

        Throwable thrown = result.get(10, TimeUnit.SECONDS);
        // Killed processes exit non-zero too; that must not look like "ffprobe refused the file".
        assertThat(thrown).isInstanceOf(TranscodeException.class).isNotInstanceOf(ToolRefusedException.class);
        assertThat(thrown).hasMessageContaining("was stopped");
    }

    @Test
    void standardOutputIsReturnedWithoutTheErrorStreamAndOddBytesAreTolerated() {
        // JSON on stdout, a warning with bytes that are not UTF-8 on stderr (media tags come in any encoding).
        String output = transcoder.run(
                List.of("sh", "-c", "printf '{\"ok\":true}'; printf 'warning \\377\\376 tag\\n' >&2"),
                dir.resolve("e.log"),
                Duration.ofSeconds(10));
        assertThat(output).isEqualTo("{\"ok\":true}");

        // The same bytes in a failing run end up in the message (as replacement characters), not as a crash.
        assertThatThrownBy(() -> transcoder.run(
                        List.of("sh", "-c", "printf 'bad \\377\\376 input\\n' >&2; exit 1"),
                        dir.resolve("f.log"),
                        Duration.ofSeconds(10)))
                .isInstanceOf(ToolRefusedException.class)
                .hasMessageContaining("bad ")
                .hasMessageContaining(" input");
    }

    @Test
    void onlyTheTailOfALongErrorLogIsQuoted() throws Exception {
        Path log = dir.resolve("long.log");
        Files.writeString(log, "x".repeat(50_000) + "\nthe last line");

        String tail = FfmpegTranscoder.tail(log);

        assertThat(tail).startsWith("…").endsWith("the last line");
        assertThat(tail.length()).isLessThan(1_000);
    }

    @Test
    void probeRejectsWhatIsNotAVideoIncludingAPlaylistDressedAsOne() throws Exception {
        Path garbage = Files.write(dir.resolve("garbage"), new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        assertThatThrownBy(() -> transcoder.probe(garbage)).isInstanceOf(MediaRejectedException.class);

        // An HLS playlist can tell FFmpeg to open other files or URLs. Only MP4/QuickTime and WebM/Matroska
        // containers are accepted, so it is refused before anything it names is touched.
        Path playlist = Files.writeString(
                dir.resolve("original"),
                "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nfile:///etc/hostname\n#EXT-X-ENDLIST\n");
        assertThatThrownBy(() -> transcoder.probe(playlist)).isInstanceOf(MediaRejectedException.class);
    }
}
