#!/usr/bin/env bash
# make aws-ingest / make aws-backfill-past-gigs: one ingestion on AWS as a one-off Fargate task (the
# Job task definition: the API image in the `cli` role), as `make ingest` / `make backfill-past-gigs`
# do locally. Waits for it to stop, prints its exit code and its last 50 log lines.
#   ops/aws/ingest.sh [--backfill]
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

args=(--soundcheck.ingestion.cli=ingest)
env_overrides='[]'
what="one gig ingestion (Ticketmaster, the venue sites, enrichment)"
if [[ ${1:-} == --backfill ]]; then
  # setlist.fm's past gigs of every act on a gig with clips; at most 800 calls a run.
  args+=(--soundcheck.ingestion.cli-sources=SETLISTFM)
  env_overrides='[{"name": "INGESTION_MODE", "value": "live"}, {"name": "INGESTION_SETLISTFM_BACKFILL", "value": "true"}]'
  what="the setlist.fm past-gigs backfill"
fi

cluster=$(out SoundcheckCompute ClusterName)
task_def=$(out SoundcheckCompute JobTaskDefinitionArn)
container=$(out SoundcheckCompute JobContainerName)
security_group=$(out SoundcheckCompute JobSecurityGroupId)
subnets=$(out SoundcheckCompute PublicSubnetIds)
log_group=$(out SoundcheckCompute JobLogGroup)

confirm "Run $what on AWS: a one-off Fargate task (1 vCPU, 2 GB; a few cents for a run of minutes)."

command_json=$(printf '%s\n' "${args[@]}" | jq -R . | jq -sc .)
overrides=$(jq -cn --arg c "$container" --argjson cmd "$command_json" --argjson env "$env_overrides" \
  '{containerOverrides: [{name: $c, command: $cmd} + (if $env == [] then {} else {environment: $env} end)]}')

# Public subnets with a public IP: there is no NAT, so that is how the task reaches ECR and the APIs.
task_arn=$(run aws ecs run-task --cluster "$cluster" --task-definition "$task_def" --launch-type FARGATE --count 1 \
  --network-configuration "awsvpcConfiguration={subnets=[$subnets],securityGroups=[$security_group],assignPublicIp=ENABLED}" \
  --overrides "$overrides" --started-by make-aws-ingest --query 'tasks[0].taskArn' --output text)
if dry; then
  say "+ (wait until the task stops, then print its exit code and the last 50 lines of $log_group)"
  exit 0
fi
[[ -n $task_arn && $task_arn != None ]] || die "the task did not start (aws ecs run-task returned no task; see its failures)"
task_id=${task_arn##*/}
say "started task $task_id; waiting for it to stop (every 15 s)"

started=$(date +%s)
timeout_s=${AWS_TASK_TIMEOUT:-5400}
while :; do
  status=$(aws ecs describe-tasks --cluster "$cluster" --tasks "$task_arn" --query 'tasks[0].lastStatus' --output text)
  [[ $status == STOPPED ]] && break
  (($(date +%s) - started < timeout_s)) || die "still $status after $timeout_s s; it keeps running: make aws-logs SERVICE=job"
  say "  $(date -u +%H:%M:%SZ) $status"
  sleep 15
done

read -r exit_code reason < <(aws ecs describe-tasks --cluster "$cluster" --tasks "$task_arn" \
  --query 'tasks[0].[containers[0].exitCode, stoppedReason]' --output text)
say "task stopped after $(($(date +%s) - started)) s: exit code $exit_code ($reason)"

stream=$(aws logs describe-log-streams --log-group-name "$log_group" --order-by LastEventTime --descending --max-items 20 \
  --query "logStreams[?ends_with(logStreamName, '/$task_id')].logStreamName | [0]" --output text 2>/dev/null || true)
if [[ -n $stream && $stream != None ]]; then
  say "last 50 log lines ($stream):"
  aws logs get-log-events --log-group-name "$log_group" --log-stream-name "$stream" --limit 50 --output json |
    jq -r '.events[].message'
else
  say "no log stream found for the task in $log_group"
fi
[[ $exit_code == 0 ]]
