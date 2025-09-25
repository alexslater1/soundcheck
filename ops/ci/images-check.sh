#!/usr/bin/env bash
# The container check: runs soundcheck-api:local and
# soundcheck-worker:local like their ECS tasks (ops/ci/compose.images.yaml) and proves the pipeline with
# ops/scripts/smoke.sh: register → upload → READY by the worker container → playlist → delete.
#
# Usage: ops/ci/images-check.sh [--restart-worker]
#   --restart-worker  instead of one smoke run: a longer clip, and the worker container is restarted while
#                     it transcodes it. The job must be released on shutdown and finished by the restarted
#                     worker. The worker's shutdown grace is 2 s here (90 s on ECS).
# Needs: `make images` first, the local stack's LocalStack on 127.0.0.1:4566, the aws CLI, curl, jq,
# ffmpeg, openssl. Uses only its own resources: compose project soundcheck-images (PostGIS on :55490, api on
# :8190, worker on :8191), and in LocalStack the bucket soundcheck-media-images and the queues
# soundcheck-transcode-images(-dlq), all deleted at the end, also after a failure.
# Exit codes: 0 passed; 1 a step or the smoke test failed; 2 usage or a missing prerequisite.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RESTART=0
case "${1:-}" in
  "") ;;
  --restart-worker) RESTART=1 ;;
  *)
    echo "usage: $0 [--restart-worker]" >&2
    exit 2
    ;;
esac

PROJECT=soundcheck-images
COMPOSE=(docker compose -p "$PROJECT" -f "$ROOT/ops/ci/compose.images.yaml")
REGION=eu-north-1
BUCKET=soundcheck-media-images
QUEUE=soundcheck-transcode-images
DLQ=soundcheck-transcode-images-dlq
API=http://127.0.0.1:8190
WORKER=http://127.0.0.1:8191
WORK="$(mktemp -d)"

# The aws CLI pointed at LocalStack with its dummy credentials: no profile, config or real endpoint is used.
localstack() {
  env -u AWS_PROFILE -u AWS_SESSION_TOKEN AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
    AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null \
    aws --endpoint-url http://localhost:4566 --region "$REGION" "$@"
}

log() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }

teardown() {
  local status=$?
  log "teardown"
  "${COMPOSE[@]}" logs --no-color api worker >"$WORK/containers.log" 2>&1 || true
  if [ "$status" != 0 ] && [ -s "$WORK/containers.log" ]; then
    echo "── last container log lines ──"
    tail -n 60 "$WORK/containers.log"
  fi
  "${COMPOSE[@]}" down --volumes --remove-orphans --timeout 130 >/dev/null 2>&1 || true
  localstack s3 rb "s3://$BUCKET" --force >/dev/null 2>&1 || true
  for name in "$QUEUE" "$DLQ"; do
    url="$(localstack sqs get-queue-url --queue-name "$name" --query QueueUrl --output text 2>/dev/null || true)"
    [ -z "$url" ] || localstack sqs delete-queue --queue-url "$url" >/dev/null 2>&1 || true
  done
  rm -rf "$WORK"
  exit "$status"
}

# The worker's job lines (structured JSON logs), with their UTC times.
worker_events() {
  "${COMPOSE[@]}" logs --no-color --no-log-prefix worker 2>/dev/null |
    jq -Rr 'fromjson? | select(.message | test("^transcode (started|released|ready|failed|rejected)"))
      | "\(.["@timestamp"][11:23])Z  worker: \(.message)"'
}

# Seconds until URL answers 200, or fail after LIMIT seconds.
wait_ready() {
  local url="$1" limit="$2" start
  start="$(date +%s)"
  until curl -sf -o /dev/null "$url"; do
    if [ $(($(date +%s) - start)) -ge "$limit" ]; then
      log "FAIL: $url not ready after ${limit}s"
      return 1
    fi
    sleep 1
  done
  echo $(($(date +%s) - start))
}

for tool in docker aws curl jq ffmpeg openssl; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "images-check: $tool is not installed" >&2
    exit 2
  }
done
for image in soundcheck-api:local soundcheck-worker:local; do
  docker image inspect "$image" >/dev/null 2>&1 || {
    echo "images-check: $image is missing (make images)" >&2
    exit 2
  }
done
curl -sf -o /dev/null http://localhost:4566/_localstack/health || {
  echo "images-check: LocalStack is not running on :4566 (make up)" >&2
  exit 2
}
trap teardown EXIT
trap 'exit 130' INT TERM # so a timeout or Ctrl-C still tears down

# ── LocalStack: our own bucket and queues (settings as ops/localstack/init/ready.d/10-resources.sh) ──
log "LocalStack: bucket $BUCKET, queues $QUEUE and $DLQ ($REGION)"
localstack s3api create-bucket --bucket "$BUCKET" --create-bucket-configuration LocationConstraint="$REGION" >/dev/null
# Public read of media/* only, as the local edge has it (CloudFront reads through OAC on AWS).
localstack s3api put-bucket-policy --bucket "$BUCKET" --policy "{\"Version\": \"2012-10-17\", \"Statement\": [{
  \"Sid\": \"LocalPublicReadMedia\", \"Effect\": \"Allow\", \"Principal\": \"*\", \"Action\": [\"s3:GetObject\"],
  \"Resource\": [\"arn:aws:s3:::$BUCKET/media/*\"]}]}"
dlq_url="$(localstack sqs create-queue --queue-name "$DLQ" --attributes MessageRetentionPeriod=1209600 \
  --query QueueUrl --output text)"
dlq_arn="$(localstack sqs get-queue-attributes --queue-url "$dlq_url" --attribute-names QueueArn \
  --query Attributes.QueueArn --output text)"
localstack sqs create-queue --queue-name "$QUEUE" --attributes "{
  \"VisibilityTimeout\": \"600\", \"ReceiveMessageWaitTimeSeconds\": \"20\",
  \"RedrivePolicy\": \"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}" >/dev/null

# ── Throwaway secrets: a JWT key made like `make keys`, and an admin password ────────────────────
openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out "$WORK/jwt-private.pem"
openssl ec -in "$WORK/jwt-private.pem" -pubout -out "$WORK/jwt-public.pem" 2>/dev/null
IMAGES_JWT_PRIVATE_KEY_PEM="$(cat "$WORK/jwt-private.pem" "$WORK/jwt-public.pem")"
IMAGES_ADMIN_PASSWORD="$(openssl rand -hex 16)"
export IMAGES_JWT_PRIVATE_KEY_PEM IMAGES_ADMIN_PASSWORD
if [ "$RESTART" = 1 ]; then
  export IMAGES_WORKER_SHUTDOWN_GRACE=2s
fi

# ── Start: database, then the API (it migrates), then the worker (no Flyway) ─────────────────────
log "starting db"
"${COMPOSE[@]}" up -d --wait db >/dev/null 2>&1
log "starting api (Flyway migrates the empty database)"
"${COMPOSE[@]}" up -d --no-deps api >/dev/null 2>&1
api_s="$(wait_ready "$API/actuator/health/readiness" 300)"
log "api ready in ${api_s}s"
log "starting worker"
"${COMPOSE[@]}" up -d --no-deps worker >/dev/null 2>&1
worker_s="$(wait_ready "$WORKER/actuator/health/readiness" 180)"
log "worker ready in ${worker_s}s"

# ── The containers are what ECS will run: non-root, read-only root, FFmpeg in the worker only ────
for service in api worker; do
  uid="$("${COMPOSE[@]}" exec -T "$service" id -u)"
  [ "$uid" = 10001 ] || { log "FAIL: $service runs as uid $uid"; exit 1; }
  if "${COMPOSE[@]}" exec -T "$service" touch /app/written 2>/dev/null; then
    log "FAIL: $service can write to its root filesystem"
    exit 1
  fi
  "${COMPOSE[@]}" exec -T "$service" touch /tmp/written || { log "FAIL: $service cannot write /tmp"; exit 1; }
done
ffmpeg_version="$("${COMPOSE[@]}" exec -T worker ffmpeg -version | head -1)"
log "both run as uid 10001 with a read-only root and a writable /tmp; worker: $ffmpeg_version"

# ── The smoke test ───────────────────────────────────────────────────────────────────────────────
if [ "$RESTART" = 0 ]; then
  log "smoke"
  "$ROOT/ops/scripts/smoke.sh" "$API"
  worker_events
else
  log "smoke with a 30 s clip; the worker is restarted while it transcodes"
  SMOKE_CLIP_SECONDS=30 SMOKE_READY_TIMEOUT=300 "$ROOT/ops/scripts/smoke.sh" "$API" >"$WORK/smoke.out" 2>&1 &
  smoke_pid=$!
  deadline=$(($(date +%s) + 180))
  until "${COMPOSE[@]}" logs --no-color worker 2>/dev/null | grep -q 'transcode started'; do
    [ "$(date +%s)" -lt "$deadline" ] || { log "FAIL: the worker never started the job"; exit 1; }
    kill -0 "$smoke_pid" 2>/dev/null || { cat "$WORK/smoke.out"; log "FAIL: smoke ended early"; exit 1; }
    sleep 0.5
  done
  log "the worker started the job; restarting the worker container"
  restart_start="$(date +%s)"
  "${COMPOSE[@]}" restart --timeout 120 worker >/dev/null 2>&1
  log "worker stopped and started again in $(($(date +%s) - restart_start))s"
  wait_ready "$WORKER/actuator/health/readiness" 180 >/dev/null
  smoke_status=0
  wait "$smoke_pid" || smoke_status=$?
  cat "$WORK/smoke.out"
  worker_events
  worker_events | grep -q 'transcode released on shutdown' || {
    log "FAIL: the worker did not release the job on shutdown"
    exit 1
  }
  [ "$smoke_status" = 0 ] || exit "$smoke_status"
fi
log "memory now: $(docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' "${PROJECT}-api-1" "${PROJECT}-worker-1" | tr '\n' ';')"
log "passed"
