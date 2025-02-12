package app.soundcheck.support;

import java.net.URI;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Creates the test bucket and queues in the shared LocalStack container (the compose stack does this
 * with its ready hook; Testcontainers has no hook, so tests do it once here).
 */
final class LocalStackResources {

    private LocalStackResources() {}

    static void create(URI endpoint, String region, String accessKey, String secretKey) {
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        try (S3Client s3 = S3Client.builder()
                        .endpointOverride(endpoint)
                        .region(Region.of(region))
                        .credentialsProvider(credentials)
                        .serviceConfiguration(S3Configuration.builder()
                                .pathStyleAccessEnabled(true)
                                .build())
                        .build();
                SqsClient sqs = SqsClient.builder()
                        .endpointOverride(endpoint)
                        .region(Region.of(region))
                        .credentialsProvider(credentials)
                        .build()) {
            s3.createBucket(
                    CreateBucketRequest.builder().bucket(IntegrationTest.BUCKET).build());
            String dlqArn = "arn:aws:sqs:" + region + ":000000000000:" + IntegrationTest.DLQ;
            sqs.createQueue(
                    CreateQueueRequest.builder().queueName(IntegrationTest.DLQ).build());
            sqs.createQueue(CreateQueueRequest.builder()
                    .queueName(IntegrationTest.QUEUE)
                    .attributes(Map.of(
                            QueueAttributeName.VISIBILITY_TIMEOUT,
                            "30",
                            QueueAttributeName.REDRIVE_POLICY,
                            "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"3\"}"))
                    .build());
        }
    }
}
