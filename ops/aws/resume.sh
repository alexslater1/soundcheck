#!/usr/bin/env bash
# make aws-resume: undoes aws-pause. Starts the database and waits until it is available, puts the
# scaling limits back to infra/cdk.json's defaults, starts the API's minimum, waits until it is
# stable, then runs aws-smoke. Resume before any make aws-deploy.
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

cluster=$(out SoundcheckCompute ClusterName)
api=$(out SoundcheckCompute ApiServiceName)
worker=$(out SoundcheckCompute WorkerServiceName)
db=$(out SoundcheckData DbInstanceId)
api_min=$(cdk_default apiMinTasks 1)
api_max=$(cdk_default apiMaxTasks 2)
worker_min=$(cdk_default workerMinTasks 0)
worker_max=$(cdk_default workerMaxTasks 2)

confirm "Resume Soundcheck '$SOUNDCHECK_ENV': start the database, API $api_min-$api_max tasks, workers $worker_min-$worker_max.
Billable again: about \$60 a month while it runs (list prices)."

status=$(ask aws rds describe-db-instances --db-instance-identifier "$db" \
  --query 'DBInstances[0].DBInstanceStatus' --output text) || status=""
# Resumed straight after a pause, the database is still stopping, and RDS refuses to start it until it
# has stopped; waiting for "available" alone would then time out (2025-10-16: about 8 minutes).
if [[ $status == stopping ]]; then
  say "the database is still stopping; waiting until it has stopped"
  while ! dry && [[ $(aws rds describe-db-instances --db-instance-identifier "$db" \
    --query 'DBInstances[0].DBInstanceStatus' --output text) == stopping ]]; do
    sleep 20
  done
  status=stopped
fi
if [[ -z $status || $status == stopped ]]; then
  run aws rds start-db-instance --db-instance-identifier "$db" --query 'DBInstance.DBInstanceStatus' --output text
fi
say "waiting for the database (usually 5-10 minutes)"
run aws rds wait db-instance-available --db-instance-identifier "$db"

run aws application-autoscaling register-scalable-target --service-namespace ecs \
  --scalable-dimension ecs:service:DesiredCount --resource-id "$(scalable_id "$cluster" "$api")" \
  --min-capacity "$api_min" --max-capacity "$api_max"
run aws application-autoscaling register-scalable-target --service-namespace ecs \
  --scalable-dimension ecs:service:DesiredCount --resource-id "$(scalable_id "$cluster" "$worker")" \
  --min-capacity "$worker_min" --max-capacity "$worker_max"
run aws ecs update-service --cluster "$cluster" --service "$api" --desired-count "$api_min" \
  --query 'service.serviceName' --output text
run aws ecs update-service --cluster "$cluster" --service "$worker" --desired-count "$worker_min" \
  --query 'service.serviceName' --output text
run aws ecs wait services-stable --cluster "$cluster" --services "$api"

"$AWS_DIR/smoke.sh"
say "Resumed."
