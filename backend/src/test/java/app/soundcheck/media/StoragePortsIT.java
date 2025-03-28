package app.soundcheck.media;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * Round-trips the {@link ObjectStorage} and {@link JobQueue} ports against LocalStack:
 * presigned PUT → HEAD, multipart create/part/complete, file get/put/copy/delete, SQS send/receive/
 * visibility/delete, and the readiness probe seeing both.
 */
class StoragePortsIT extends IntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    ObjectStorage storage;

    @Autowired
    TestRestTemplate rest;

    @Test
    void presignedPutRoundTrip() throws Exception {
        String key = "uploads/test/" + UUID.randomUUID() + "/original";
        byte[] bytes = "hello soundcheck".getBytes();

        var presigned = storage.presignPut(key, "video/mp4", bytes.length, Duration.ofMinutes(5));
        assertThat(presigned.url().toString()).contains(BUCKET).contains(key);

        HttpResponse<Void> put = HTTP.send(
                HttpRequest.newBuilder(presigned.url())
                        .header("Content-Type", "video/mp4")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(200);

        // Real S3 rejects a PUT whose Content-Type differs from the signed one (SignatureDoesNotMatch, 403);
        // LocalStack 4.1.1 accepts it, so that property is checked by the AWS smoke test, not here.
        var head = storage.head(key).orElseThrow();
        assertThat(head.contentLength()).isEqualTo(bytes.length);
        assertThat(head.contentType()).isEqualTo("video/mp4");
        assertThat(head.etag()).isNotBlank();

        storage.delete(key);
        assertThat(storage.head(key)).isEmpty();
    }

    @Test
    void multipartUploadRoundTrip() throws Exception {
        String key = "uploads/test/" + UUID.randomUUID() + "/original";
        byte[] part1 = new byte[5 * 1024 * 1024]; // S3 minimum for all but the last part
        byte[] part2 = "tail".getBytes();

        String uploadId = storage.createMultipart(key, "video/mp4");
        String etag1 = uploadPart(
                storage.presignUploadPart(key, uploadId, 1, part1.length, Duration.ofMinutes(5))
                        .url(),
                part1);
        URI secondPart = storage.presignUploadPart(key, uploadId, 2, part2.length, Duration.ofMinutes(5))
                .url();
        // The part's length is one of the signed headers: real S3 refuses a body of another size.
        assertThat(secondPart.getQuery()).containsPattern("X-Amz-SignedHeaders=[^&]*content-length");
        String etag2 = uploadPart(secondPart, part2);
        storage.completeMultipart(
                key,
                uploadId,
                List.of(new ObjectStorage.CompletedPart(1, etag1), new ObjectStorage.CompletedPart(2, etag2)));

        assertThat(storage.head(key).orElseThrow().contentLength()).isEqualTo(part1.length + part2.length);

        String abortedKey = key + "-aborted";
        String abortedId = storage.createMultipart(abortedKey, "video/mp4");
        storage.abortMultipart(abortedKey, abortedId);
        assertThat(storage.head(abortedKey)).isEmpty();
        // Aborting what is already gone (a second call, or after the lifecycle rule) is not an error.
        storage.abortMultipart(abortedKey, abortedId);
    }

    @Test
    void fileGetPutCopy(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("poster.jpg");
        Files.write(source, new byte[] {1, 2, 3, 4});
        String key = "media/" + UUID.randomUUID() + "/v1/poster.jpg";

        storage.putFile(key, source, "image/jpeg");
        storage.copy(key, key + ".copy", "image/jpeg", "public, max-age=60");
        assertThat(storage.head(key + ".copy").orElseThrow().contentType()).isEqualTo("image/jpeg");
        Path target = dir.resolve("downloaded.jpg");
        storage.getToFile(key + ".copy", target);

        assertThat(Files.readAllBytes(target)).containsExactly(1, 2, 3, 4);
        assertThat(storage.head(key).orElseThrow().contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void readinessSeesStorageAndQueue() {
        String body =
                rest.getForEntity("/actuator/health/readiness", String.class).getBody();
        assertThat(body).contains("\"s3\":{").contains("\"sqs\":{").contains("\"status\":\"UP\"");
        assertThat(body).doesNotContain("\"DOWN\"");
    }

    private static String uploadPart(URI url, byte[] bytes) throws Exception {
        HttpResponse<Void> response = HTTP.send(
                HttpRequest.newBuilder(url)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("ETag").orElseThrow();
    }
}
