package app.soundcheck.media.internal.aws;

import app.soundcheck.media.JobQueue;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** {@link JobQueue} on the AWS SDK v2 SQS client (real SQS or LocalStack, see {@link AwsClientsConfig}). */
@Component
class SqsJobQueue implements JobQueue {

    private final SqsClient sqs;
    private final String queueUrl;

    SqsJobQueue(SqsClient sqs, QueueProperties properties) {
        this.sqs = sqs;
        this.queueUrl = properties.url();
    }

    @Override
    public String send(String body, Map<String, String> attributes) {
        Map<String, MessageAttributeValue> messageAttributes = attributes.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(e.getValue())
                        .build()));
        return sqs.sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(body)
                        .messageAttributes(messageAttributes)
                        .build())
                .messageId();
    }

    @Override
    public List<QueueMessage> receive(int maxMessages, Duration wait) {
        return sqs
                .receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(maxMessages)
                        .waitTimeSeconds((int) wait.toSeconds())
                        .messageAttributeNames("All")
                        .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                        .build())
                .messages()
                .stream()
                .map(SqsJobQueue::toQueueMessage)
                .toList();
    }

    @Override
    public void delete(String receiptHandle) {
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .build());
    }

    @Override
    public void changeVisibility(String receiptHandle, Duration timeout) {
        sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .visibilityTimeout((int) timeout.toSeconds())
                .build());
    }

    @Override
    public QueueAttributes attributes() {
        Map<QueueAttributeName, String> attrs = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED)
                        .build())
                .attributes();
        return new QueueAttributes(
                parse(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)),
                parse(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)),
                parse(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED)));
    }

    private static QueueMessage toQueueMessage(Message m) {
        Map<String, String> attributes = m.messageAttributes().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().stringValue()));
        int receiveCount = parse(m.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT));
        return new QueueMessage(m.messageId(), m.receiptHandle(), m.body(), attributes, receiveCount);
    }

    private static int parse(String value) {
        return value == null ? 0 : Integer.parseInt(value);
    }
}
