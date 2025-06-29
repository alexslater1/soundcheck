package app.soundcheck.media.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.soundcheck.media.ObjectStorage;
import app.soundcheck.shared.config.JsonMappers;
import app.soundcheck.shared.idempotency.IdempotencyKeys;
import app.soundcheck.shared.observability.Traceparent;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.RateLimiter;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

/**
 * An avatar is downloaded, checked and re-encoded <em>before</em> the
 * upload's transaction locks its row, so the lock and the database connection are not held while an
 * image of up to 10 MB is decoded. The storage and the repository record what happened, and whether a
 * transaction was open at that moment, in one list.
 */
class UploadServiceImageOrderTest {

    static final UUID OWNER = UUID.fromString("01a0fe00-0000-7000-8000-000000000001");
    static final UUID MEDIA = UUID.fromString("01a0fe00-0000-7000-8000-000000000002");
    static final String ORIGINAL = "uploads/" + OWNER + "/" + MEDIA + "/original";
    static final String SERVED = "media/" + MEDIA + "/v1/image.png";

    @TempDir
    Path dir;

    final List<String> log = new ArrayList<>();
    final RecordingTransactions transactions = new RecordingTransactions(log);
    final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    final ObjectStorage storage = mock(ObjectStorage.class);
    Path png;
    UploadService service;

    @BeforeEach
    void setUp() throws IOException {
        png = dir.resolve("avatar.png");
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", png.toFile());

        when(assets.find(MEDIA)).thenReturn(Optional.of(image("AWAITING_UPLOAD")));
        when(storage.head(ORIGINAL)).thenAnswer(inv -> {
            log.add("head " + transactions.state());
            return Optional.of(new ObjectStorage.ObjectHead(Files.size(png), "image/png", "etag", Instant.EPOCH));
        });
        doAnswer(inv -> {
                    log.add("download " + transactions.state());
                    Files.copy(png, inv.<Path>getArgument(1));
                    return null;
                })
                .when(storage)
                .getToFile(eq(ORIGINAL), any(Path.class));
        doAnswer(inv -> {
                    log.add("store cleaned copy " + transactions.state());
                    return null;
                })
                .when(storage)
                .putFile(eq(SERVED), any(Path.class), eq("image/png"), anyString());
        when(assets.markUploaded(eq(MEDIA), eq("UPLOADED"), anyLong(), any())).thenAnswer(inv -> {
            log.add("mark uploaded " + transactions.state());
            return 1;
        });
        doAnswer(inv -> {
                    log.add("mark ready " + transactions.state());
                    return null;
                })
                .when(assets)
                .markImageReady(eq(MEDIA), anyString(), eq(SERVED), any());

        service = new UploadService(
                assets,
                storage,
                mock(IdempotencyKeys.class),
                mock(RateLimiter.class),
                mock(ApplicationEventPublisher.class),
                new MediaProperties(
                        DataSize.ofMegabytes(500),
                        DataSize.ofMegabytes(10),
                        Duration.ofSeconds(61),
                        DataSize.ofMegabytes(16),
                        DataSize.ofMegabytes(8),
                        Duration.ofMinutes(60),
                        30,
                        "http://localhost:8088"),
                JsonMappers.builder().build(),
                mock(Traceparent.class),
                new TransactionTemplate(transactions),
                Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void theImageIsCleanedBeforeTheRowIsLocked() {
        when(assets.findForUpdate(MEDIA)).thenAnswer(inv -> {
            log.add("lock row " + transactions.state());
            return Optional.of(image("AWAITING_UPLOAD"));
        });

        assertThat(service.complete(OWNER, MEDIA, List.of())).isEqualTo("READY");

        assertThat(log)
                .containsExactly(
                        "head outside a transaction",
                        "download outside a transaction",
                        "store cleaned copy outside a transaction",
                        "begin",
                        "lock row in a transaction",
                        "mark uploaded in a transaction",
                        "mark ready in a transaction",
                        "commit");
    }

    @Test
    void aCopyCleanedForAnUploadDeletedMeanwhileIsRemoved() {
        // The owner deleted the upload while its image was being cleaned: nothing will ever serve the copy.
        when(assets.findForUpdate(MEDIA)).thenReturn(Optional.of(image("DELETED")));

        assertThatThrownBy(() -> service.complete(OWNER, MEDIA, List.of()))
                .isInstanceOf(ApiException.class)
                .hasMessage("No such media");

        verify(storage).delete(SERVED);
        assertThat(log).endsWith("rollback");
    }

    @Test
    void aRefusedImageIsMarkedRejectedAndTheRefusalStillCommits() throws IOException {
        Files.writeString(png, "not an image at all"); // header and bytes disagree with image/png
        when(assets.findForUpdate(MEDIA)).thenReturn(Optional.of(image("AWAITING_UPLOAD")));

        assertThatThrownBy(() -> service.complete(OWNER, MEDIA, List.of()))
                .isInstanceOf(ApiException.class)
                .hasMessage("The uploaded file is not a valid image");

        verify(assets).close(eq(MEDIA), eq("UPLOADED"), eq("REJECTED"), anyString(), any());
        verify(storage).delete(ORIGINAL);
        assertThat(log).endsWith("commit").doesNotContain("store cleaned copy outside a transaction");
    }

    static MediaAsset image(String status) {
        return new MediaAsset(
                MEDIA,
                OWNER,
                "IMAGE",
                status,
                "image/png",
                100,
                ORIGINAL,
                "SINGLE",
                null,
                null,
                null,
                16,
                16,
                null,
                null,
                null,
                null,
                0,
                null,
                Instant.EPOCH);
    }

    /** A transaction manager that only records begin, commit and rollback, and whether one is open. */
    static final class RecordingTransactions implements PlatformTransactionManager {
        private final List<String> log;
        private boolean open;

        RecordingTransactions(List<String> log) {
            this.log = log;
        }

        String state() {
            return open ? "in a transaction" : "outside a transaction";
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            open = true;
            log.add("begin");
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            open = false;
            log.add("commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            open = false;
            log.add("rollback");
        }
    }
}
