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

    /**
     * Presigned PUT for one part (1-based) of a multipart upload. The part's length is part of the
     * signature: without it a part URL would accept anything up to S3's 5 GiB part limit.
     */
    PresignedUrl presignUploadPart(String key, String uploadId, int partNumber, long contentLength, Duration ttl);

    /**
     * Completes a multipart upload from the parts' ETags, in part order.
     *
     * @throws InvalidUploadException when storage rejects the request: unknown upload id, a missing or
     *     mismatching part, or a part that is too small
     */
    void completeMultipart(String key, String uploadId, List<CompletedPart> parts);

    /** Abandons a multipart upload; an upload that no longer exists (already aborted or expired) is not an error. */
    void abortMultipart(String key, String uploadId);

    /** Object metadata, or empty if the key does not exist. */
    Optional<ObjectHead> head(String key);

    void getToFile(String key, Path target);

    void putFile(String key, Path source, String contentType);

    /** Uploads a file with a {@code Cache-Control} header (versioned media outputs are immutable). */
    void putFile(String key, Path source, String contentType, String cacheControl);

    /** Copies an object within the bucket, giving the copy its own content type and {@code Cache-Control}. */
    void copy(String sourceKey, String targetKey, String contentType, String cacheControl);

    void delete(String key);

    /**
     * Deletes every object whose key starts with the prefix (account purge). Safe to repeat.
     *
     * @return how many objects were deleted
     */
    int deletePrefix(String prefix);

    /** The client's description of what it uploaded does not match what storage holds. */
    class InvalidUploadException extends RuntimeException {
        public InvalidUploadException(String message) {
            super(message);
        }
    }

    /** A URL a client can use until {@code expiresAt}; never logged. */
    record PresignedUrl(URI url, Instant expiresAt) {}

    /** One uploaded part of a multipart upload. */
    record CompletedPart(int partNumber, String etag) {}

    /** Object metadata from HEAD. */
    record ObjectHead(long contentLength, String contentType, String etag, Instant lastModified) {}
}
