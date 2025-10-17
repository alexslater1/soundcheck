#!/usr/bin/env bash
# make aws-warm / make aws-cool: keep one transcode worker running for a demo, then let workers scale
# from zero again. Workers normally start only when a clip is queued, and a start from zero takes about
# 4½ minutes (272 s measured 2025-10-16); the idle alarm stops the last one after 15 minutes of an
# empty queue. Warm sets the scaling minimum to 1, so nothing stops it; cool puts it back to 0.
#   ops/aws/worker.sh warm   billable until cooled (asks)
#   ops/aws/worker.sh cool   the minimum back to 0; a running worker stops after 15 idle minutes
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

mode=${1:-}
[[ $mode == warm || $mode == cool ]] || die "usage: $0 warm|cool"

cluster=$(out SoundcheckCompute ClusterName)
worker=$(out SoundcheckCompute WorkerServiceName)
target=$(scalable_id "$cluster" "$worker")
worker_max=$(cdk_default workerMaxTasks 2)

# The worker's current scaling maximum (empty offline).
max=$(ask aws application-autoscaling describe-scalable-targets --service-namespace ecs \
  --resource-ids "$target" --query 'ScalableTargets[0].MaxCapacity' --output text) || max=""

if [[ $mode == warm ]]; then
  # aws-pause leaves the maximum at 0, and the database stopped: a worker would only fail to start.
  [[ $max != 0 ]] || die "Soundcheck is paused (worker maximum 0): run make aws-resume first"
  confirm "Warm one worker on '$SOUNDCHECK_ENV': its scaling minimum goes to 1, so one worker task (4 vCPU, 8 GB,
mostly Spot) keeps running until make aws-cool or make aws-pause. Billable the whole time: \$0.22 an hour on-demand at list prices,
less on Spot (an estimate, not a measured bill)."
  run aws application-autoscaling register-scalable-target --service-namespace ecs \
    --scalable-dimension ecs:service:DesiredCount --resource-id "$target" \
    --min-capacity 1 --max-capacity "$worker_max"
  desired=$(ask aws ecs describe-services --cluster "$cluster" --services "$worker" \
    --query 'services[0].desiredCount' --output text) || desired=""
  # Raised only from 0: a worker already running (or two, while the queue is busy) is left alone.
  if [[ -z $desired || $desired == 0 ]]; then
    run aws ecs update-service --cluster "$cluster" --service "$worker" --desired-count 1 \
      --query 'service.serviceName' --output text
  fi
  say "waiting for the worker to be running (from zero about 4½ minutes)"
  started=$(date +%s)
  run aws ecs wait services-stable --cluster "$cluster" --services "$worker"
  say ""
  say "Warm after $(($(date +%s) - started)) s. A clip posted now goes straight to this worker."
  say "It keeps running (and billing) until make aws-cool."
else
  run aws application-autoscaling register-scalable-target --service-namespace ecs \
    --scalable-dimension ecs:service:DesiredCount --resource-id "$target" \
    --min-capacity 0 --max-capacity "$worker_max"
  say ""
  say "Cooled: workers scale from zero again. A worker running now stops by itself after 15 minutes"
  say "with an empty queue (the idle alarm), so a transcode in progress finishes first."
fi
