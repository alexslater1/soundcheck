package app.soundcheck.media.internal.aws;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * AWS SDK v2 clients. With an endpoint override (LocalStack) they use static credentials and path-style
 * S3 URLs; without one (AWS) the default credentials chain (task role) and virtual-host style.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({StorageProperties.class, QueueProperties.class})
class AwsClientsConfig {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    AwsCredentialsProvider awsCredentialsProvider(StorageProperties storage) {
        if (storage.endpointOverride().isPresent()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(storage.accessKey(), storage.secretKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean
    S3Client s3Client(StorageProperties storage, AwsCredentialsProvider credentials) {
        var builder = S3Client.builder()
                .region(Region.of(storage.region()))
                .credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(CALL_TIMEOUT)
                        .build())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(storage.endpointOverride().isPresent())
                        .build());
        storage.endpointOverride().ifPresent(builder::endpointOverride);
        return builder.build();
    }

    @Bean
    S3Presigner s3Presigner(StorageProperties storage, AwsCredentialsProvider credentials) {
        var builder = S3Presigner.builder()
                .region(Region.of(storage.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(storage.endpointOverride().isPresent())
                        .build());
        storage.endpointOverride().ifPresent(builder::endpointOverride);
        return builder.build();
    }

    @Bean
    SqsClient sqsClient(StorageProperties storage, AwsCredentialsProvider credentials) {
        var builder = SqsClient.builder()
                .region(Region.of(storage.region()))
                .credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(CALL_TIMEOUT)
                        .build());
        storage.endpointOverride().ifPresent(builder::endpointOverride);
        return builder.build();
    }
}
