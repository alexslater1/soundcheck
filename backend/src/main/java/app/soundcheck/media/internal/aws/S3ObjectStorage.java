package app.soundcheck.media.internal.aws;

import app.soundcheck.media.ObjectStorage;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

/** {@link ObjectStorage} on the AWS SDK v2 S3 client (real S3 or LocalStack, see {@link AwsClientsConfig}). */
@Component
class S3ObjectStorage implements ObjectStorage {

    /**
     * Reading or writing a whole file may take far longer than the 30 s the client allows an ordinary call
     * (a 500 MB original on a slow link): transfers get their own limit.
     */
    private static final AwsRequestOverrideConfiguration TRANSFER = AwsRequestOverrideConfiguration.builder()
            .apiCallTimeout(Duration.ofMinutes(15))
            .build();

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    S3ObjectStorage(S3Client s3, S3Presigner presigner, StorageProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = properties.bucket();
    }

    @Override
    public PresignedUrl presignPut(String key, String contentType, long contentLength, Duration ttl) {
        var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .contentLength(contentLength)
                        .build())
                .build());
        return new PresignedUrl(URI.create(presigned.url().toString()), presigned.expiration());
    }

    @Override
    public String createMultipart(String key, String contentType) {
        return s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build())
                .uploadId();
    }

    @Override
    public PresignedUrl presignUploadPart(
            String key, String uploadId, int partNumber, long contentLength, Duration ttl) {
        var presigned = presigner.presignUploadPart(UploadPartPresignRequest.builder()
                .signatureDuration(ttl)
                .uploadPartRequest(UploadPartRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                        .contentLength(contentLength)
                        .build())
                .build());
        return new PresignedUrl(URI.create(presigned.url().toString()), presigned.expiration());
    }

    @Override
    public void completeMultipart(String key, String uploadId, List<CompletedPart> parts) {
        var completed = parts.stream()
                .map(p -> software.amazon.awssdk.services.s3.model.CompletedPart.builder()
                        .partNumber(p.partNumber())
                        .eTag(p.etag())
                        .build())
                .toList();
        try {
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(
                            CompletedMultipartUpload.builder().parts(completed).build())
                    .build());
        } catch (S3Exception e) {
            // 4xx: the upload id is unknown, or a part is missing, mismatching or too small. That is the
            // client's description being wrong, not storage being down.
            if (e.statusCode() >= 400 && e.statusCode() < 500) {
                throw new InvalidUploadException(
                        e.awsErrorDetails() == null
                                ? "invalid multipart upload"
                                : e.awsErrorDetails().errorCode());
            }
            throw e;
        }
    }

    @Override
    public void abortMultipart(String key, String uploadId) {
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .build());
        } catch (NoSuchUploadException e) {
            // Already aborted (by an earlier call, or by the bucket's lifecycle rule after a day): done.
        }
    }

    @Override
    public Optional<ObjectHead> head(String key) {
        try {
            HeadObjectResponse head = s3.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(
                    new ObjectHead(head.contentLength(), head.contentType(), head.eTag(), head.lastModified()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void getToFile(String key, Path target) {
        s3.getObject(
                GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .overrideConfiguration(TRANSFER)
                        .build(),
                target);
    }

    @Override
    public void putFile(String key, Path source, String contentType) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .overrideConfiguration(TRANSFER)
                        .build(),
                source);
    }

    @Override
    public void putFile(String key, Path source, String contentType, String cacheControl) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .cacheControl(cacheControl)
                        .overrideConfiguration(TRANSFER)
                        .build(),
                source);
    }

    @Override
    public void copy(String sourceKey, String targetKey, String contentType, String cacheControl) {
        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket)
                .sourceKey(sourceKey)
                .destinationBucket(bucket)
                .destinationKey(targetKey)
                // REPLACE: the copy gets the headers given here instead of inheriting the original's.
                .metadataDirective(MetadataDirective.REPLACE)
                .contentType(contentType)
                .cacheControl(cacheControl)
                .build());
    }

    @Override
    public int deletePrefix(String prefix) {
        if (prefix.isBlank() || !prefix.endsWith("/")) {
            // "media/abc" would also match "media/abcd…": only whole "folders" may be removed.
            throw new IllegalArgumentException("a prefix to delete must end with '/'");
        }
        int deleted = 0;
        for (ListObjectsV2Response page :
                s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix))) {
            List<ObjectIdentifier> keys = page.contents().stream()
                    .map(object -> ObjectIdentifier.builder().key(object.key()).build())
                    .toList();
            if (!keys.isEmpty()) {
                s3.deleteObjects(b -> b.bucket(bucket).delete(d -> d.objects(keys)));
                deleted += keys.size();
            }
        }
        return deleted;
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
