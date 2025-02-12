#!/usr/bin/env bash
# LocalStack "ready" hook: creates the S3 bucket and SQS queues the app expects.
# Idempotent — safe to re-run on every container start. Runs inside the LocalStack container.
set -euo pipefail

BUCKET="${S3_BUCKET:-soundcheck-media}"
REGION="${AWS_DEFAULT_REGION:-eu-west-2}"
QUEUE="soundcheck-transcode"
DLQ="soundcheck-transcode-dlq"

# awslocal ships in the image; fall back to the plain CLI pointed at the local endpoint.
if command -v awslocal >/dev/null 2>&1; then
  aws_() { awslocal "$@"; }
else
  aws_() { aws --endpoint-url "http://localhost:4566" --region "$REGION" "$@"; }
fi

echo "[init] creating bucket $BUCKET"
aws_ s3api head-bucket --bucket "$BUCKET" 2>/dev/null \
  || aws_ s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
       --create-bucket-configuration LocationConstraint="$REGION"

# CORS: the app PUTs directly to presigned URLs from the Expo dev server and the edge; ETag must be
# readable for multipart completion.
aws_ s3api put-bucket-cors --bucket "$BUCKET" --cors-configuration '{
  "CORSRules": [{
    "AllowedOrigins": ["http://localhost:8081", "http://localhost:8088"],
    "AllowedMethods": ["PUT", "GET", "HEAD"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3000
  }]
}'

# Lifecycle: abort incomplete multipart uploads after 1 day; originals under uploads/ → Standard-IA after 30 days.
aws_ s3api put-bucket-lifecycle-configuration --bucket "$BUCKET" --lifecycle-configuration '{
  "Rules": [
    {
      "ID": "abort-incomplete-multipart",
      "Status": "Enabled",
      "Filter": {"Prefix": ""},
      "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 1}
    },
    {
      "ID": "uploads-to-ia",
      "Status": "Enabled",
      "Filter": {"Prefix": "uploads/"},
      "Transitions": [{"Days": 30, "StorageClass": "STANDARD_IA"}]
    }
  ]
}'

# LOCAL ONLY: public read of media/* so Caddy can serve HLS output like CloudFront does with OAC.
# On AWS the bucket blocks public access and CloudFront reads through OAC.
aws_ s3api put-bucket-policy --bucket "$BUCKET" --policy "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [{
    \"Sid\": \"LocalPublicReadMedia\",
    \"Effect\": \"Allow\",
    \"Principal\": \"*\",
    \"Action\": [\"s3:GetObject\"],
    \"Resource\": [\"arn:aws:s3:::$BUCKET/media/*\"]
  }]
}"

echo "[init] creating queues $QUEUE and $DLQ"
DLQ_URL=$(aws_ sqs create-queue --queue-name "$DLQ" \
  --attributes '{"MessageRetentionPeriod": "1209600"}' --query QueueUrl --output text)
DLQ_ARN=$(aws_ sqs get-queue-attributes --queue-url "$DLQ_URL" --attribute-names QueueArn \
  --query Attributes.QueueArn --output text)

# Visibility 10 min; redrive to the DLQ after 3 receives.
aws_ sqs create-queue --queue-name "$QUEUE" --attributes "{
  \"VisibilityTimeout\": \"600\",
  \"ReceiveMessageWaitTimeSeconds\": \"20\",
  \"RedrivePolicy\": \"{\\\"deadLetterTargetArn\\\":\\\"$DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"
}" >/dev/null

echo "[init] done: bucket=$BUCKET queue=$QUEUE dlq=$DLQ"
