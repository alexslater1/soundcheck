package app.soundcheck.media.internal.aws;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Readiness contributors: the process is ready only when the bucket and the queue answer.
 * Registered as beans named {@code s3} and {@code sqs} so they appear under those keys in
 * {@code /actuator/health/readiness}.
 */
@Configuration(proxyBeanMethods = false)
class AwsHealthIndicators {

    @Bean
    HealthIndicator s3HealthIndicator(S3Client s3, StorageProperties storage) {
        return () -> {
            try {
                s3.headBucket(
                        HeadBucketRequest.builder().bucket(storage.bucket()).build());
                return Health.up().withDetail("bucket", storage.bucket()).build();
            } catch (RuntimeException e) {
                return Health.down()
                        .withDetail("bucket", storage.bucket())
                        .withDetail("error", e.getClass().getSimpleName())
                        .build();
            }
        };
    }

    @Bean
    HealthIndicator sqsHealthIndicator(SqsClient sqs, QueueProperties queue) {
        return () -> {
            try {
                String visible = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                                .queueUrl(queue.url())
                                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                                .build())
                        .attributes()
                        .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
                return Health.up().withDetail("visible", visible).build();
            } catch (RuntimeException e) {
                return Health.down()
                        .withDetail("error", e.getClass().getSimpleName())
                        .build();
            }
        };
    }
}
