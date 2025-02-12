package app.soundcheck.media.internal.aws;

import java.net.URI;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code soundcheck.storage.*} ({@code S3_ENDPOINT}, {@code S3_BUCKET}, {@code AWS_REGION}).
 * A blank endpoint means real AWS (task-role credentials, virtual-host style); a set endpoint means
 * LocalStack (static credentials, path-style access).
 */
@ConfigurationProperties(prefix = "soundcheck.storage")
public record StorageProperties(String endpoint, String region, String bucket, String accessKey, String secretKey) {

    public Optional<URI> endpointOverride() {
        return endpoint == null || endpoint.isBlank() ? Optional.empty() : Optional.of(URI.create(endpoint));
    }
}
