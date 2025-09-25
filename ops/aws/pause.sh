#!/usr/bin/env bash
# make aws-pause: the cost brake. API and workers scale to zero and stay there (their scaling limits
# are set to 0 too, or the scaling policies would start them again); then the database stops.
# CloudFront, the ALB, storage and the secrets stay, so the address and the data survive.
# Resume with make aws-resume, and always resume before any make aws-deploy.
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

cluster=$(out SoundcheckCompute ClusterName)
api=$(out SoundcheckCompute ApiServiceName)
worker=$(out SoundcheckCompute WorkerServiceName)
db=$(out SoundcheckData DbInstanceId)

confirm "Pause Soundcheck '$SOUNDCHECK_ENV': API and workers to 0 tasks, then stop the database.
The site answers 503 until make aws-resume. A transcode in progress is put back on the queue.
While paused it still costs about \$25 a month (the ALB, storage, public IPv4 and secrets; list prices)."

for service in "$api" "$worker"; do
  run aws application-autoscaling register-scalable-target --service-namespace ecs \
    --scalable-dimension ecs:service:DesiredCount --resource-id "$(scalable_id "$cluster" "$service")" \
    --min-capacity 0 --max-capacity 0
  run aws ecs update-service --cluster "$cluster" --service "$service" --desired-count 0 \
    --query 'service.serviceName' --output text
done
# The worker gets up to 120 s to hand its job back (stopTimeout) before the database goes.
run aws ecs wait services-stable --cluster "$cluster" --services "$api" "$worker"

status=$(ask aws rds describe-db-instances --db-instance-identifier "$db" \
  --query 'DBInstances[0].DBInstanceStatus' --output text) || status=""
if [[ -z $status || $status == available ]]; then
  run aws rds stop-db-instance --db-instance-identifier "$db" --query 'DBInstance.DBInstanceStatus' --output text
else
  say "database is $status: not stopping it"
fi

say ""
say "Paused. Still billed while paused: about \$25 a month (list prices)."
say "AWS starts a stopped database again by itself after 7 days: run make aws-pause again then."
say "Resume with make aws-resume, and resume before any make aws-deploy."
