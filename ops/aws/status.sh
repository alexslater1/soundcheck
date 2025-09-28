#!/usr/bin/env bash
# make aws-status (read-only): what is running and waiting, in one screen.
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

url=$(out SoundcheckEdge PublicBaseUrl)
cluster=$(out SoundcheckCompute ClusterName)
say "Soundcheck '$SOUNDCHECK_ENV' at $url"

say ""
say "Services (desired / running / pending, and the latest deployment)"
# shellcheck disable=SC2016 # the backticks are JMESPath literals, not shell
ask aws ecs describe-services --cluster "$cluster" \
  --services "$(out SoundcheckCompute ApiServiceName)" "$(out SoundcheckCompute WorkerServiceName)" \
  --query 'services[].{service: serviceName, desired: desiredCount, running: runningCount, pending: pendingCount, rollout: deployments[?status==`PRIMARY`] | [0].rolloutState, reason: deployments[?status==`PRIMARY`] | [0].rolloutStateReason}' \
  --output table

say ""
say "Database"
ask aws rds describe-db-instances --db-instance-identifier "$(out SoundcheckData DbInstanceId)" \
  --query 'DBInstances[0].{status: DBInstanceStatus, class: DBInstanceClass, engine: EngineVersion}' --output table

say ""
say "Transcode queue (visible = waiting, in flight = being transcoded) and dead-letter queue"
ask aws sqs get-queue-attributes --queue-url "$(out SoundcheckData QueueUrl)" \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
  --query '{visible: Attributes.ApproximateNumberOfMessages, in_flight: Attributes.ApproximateNumberOfMessagesNotVisible}' --output table
ask aws sqs get-queue-attributes --queue-url "$(out SoundcheckData DlqUrl)" \
  --attribute-names ApproximateNumberOfMessages \
  --query '{dead_letters: Attributes.ApproximateNumberOfMessages}' --output table
