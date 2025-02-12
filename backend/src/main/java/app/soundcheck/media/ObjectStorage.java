package app.soundcheck.media;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Object storage port: the API signs URLs so clients upload straight to
 * S3; the worker reads originals and writes renditions. Implemented by {@code S3ObjectStorage} against
 * real S3 or LocalStack (endpoint override + path-style access).
 */
public interface ObjectStorage {

    /** Presigned single PUT for small uploads; content type and length are part of the signature. */
    PresignedUrl presignPut(String key, String contentType, long contentLength, Duration ttl);

    /** Starts a multipart upload and returns its id. */
    String createMultipart(String key, String contentType);

    /** Presigned PUT for one part (1-based) of a multipart upload. */
    PresignedUrl presignUploadPart(String key, String uploadId, int partNumber, Duration ttl);

    /** Completes a multipart upload from the parts' ETags, in part order. */
    void completeMultipart(String key, String uploadId, List<CompletedPart> parts);

    void abortMultipart(String key, String uploadId);

    /** Object metadata, or empty if the key does not exist. */
    Optional<ObjectHead> head(String key);

    void getToFile(String key, Path target);

    void putFile(String key, Path source, String contentType);

    void copy(String sourceKey, String targetKey);

    void delete(String key);

    /** A URL a client can use until {@code expiresAt}; never logged. */
    record PresignedUrl(URI url, Instant expiresAt) {}

    /** One uploaded part of a multipart upload. */
    record CompletedPart(int partNumber, String etag) {}

    /** Object metadata from HEAD. */
    record ObjectHead(long contentLength, String contentType, String etag, Instant lastModified) {}
}
