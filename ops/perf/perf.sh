#!/usr/bin/env bash
# The perf stack: `make perf-up | perf-down | perf-reset | perf-api | perf-api-stop | perf-psql`.
# Postgres runs in its own compose project (soundcheck-perf, 127.0.0.1:55500); the API is the normal jar
# with profile `perf` on 8201. Nothing here touches the local stack's compose project, its database or its
# bucket and queue: only `soundcheck-media-perf` and `soundcheck-transcode-perf` in the shared LocalStack.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE=(docker compose -f "$ROOT/ops/perf/compose.perf.yaml")
STATE="$ROOT/.localdata/perf"
LOCK="${HEAVY_LOCK:-/home/alex/Documents/Soundcheck/.localdata/locks/heavy.lock}"
LOCALSTACK="${PERF_S3_ENDPOINT:-http://localhost:4566}"
REGION="${AWS_REGION:-eu-west-2}"
BUCKET=soundcheck-media-perf
QUEUE=soundcheck-transcode-perf
PORT="${PERF_API_PORT:-8201}"
# The API's CPUs (taskset). The measured split comes from `lscpu -e`; two P-cores by default.
API_CPUS="${PERF_API_CPUS:-0,2}"
API_XMX="${PERF_API_XMX:-1g}"
# The API stops itself after this long, so a forgotten one never runs for days.
API_TIMEOUT="${PERF_API_TIMEOUT:-3h}"

mkdir -p "$STATE" "$(dirname "$LOCK")"

aws_local() {
  AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_PAGER="" \
    aws --endpoint-url "$LOCALSTACK" --region "$REGION" "$@"
}

localstack_up() { curl -sf -m 2 "$LOCALSTACK/_localstack/health" >/dev/null 2>&1; }

storage_create() {
  if ! localstack_up; then
    echo "LocalStack is not running at $LOCALSTACK: skipped $BUCKET and $QUEUE (the feed and gig endpoints don't need them; uploads do)."
    return 0
  fi
  aws_local s3api head-bucket --bucket "$BUCKET" 2>/dev/null \
    || aws_local s3api create-bucket --bucket "$BUCKET" \
         --create-bucket-configuration LocationConstraint="$REGION" >/dev/null
  aws_local sqs create-queue --queue-name "$QUEUE" >/dev/null
  echo "LocalStack: $BUCKET and $QUEUE ready."
}

storage_delete() {
  if ! localstack_up; then
    echo "LocalStack is not running at $LOCALSTACK: nothing to delete there."
    return 0
  fi
  if aws_local s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
    aws_local s3 rb "s3://$BUCKET" --force >/dev/null
    echo "LocalStack: deleted bucket $BUCKET."
  fi
  url=$(aws_local sqs get-queue-url --queue-name "$QUEUE" --query QueueUrl --output text 2>/dev/null || true)
  if [ -n "$url" ] && [ "$url" != None ]; then
    aws_local sqs delete-queue --queue-url "$url"
    echo "LocalStack: deleted queue $QUEUE."
  fi
}

api_pid() {
  local pid
  pid=$(cat "$STATE/api.pid" 2>/dev/null || true)
  # Only a live java process listening on our port counts (never signal a stale or reused PID).
  if [ -n "$pid" ] && ss -ltnp 2>/dev/null | grep ":$PORT " | grep -q "pid=$pid,"; then
    echo "$pid"
  fi
}

case "${1:-}" in
  up)
    "${COMPOSE[@]}" up -d --wait db
    storage_create
    echo "Perf Postgres: postgresql://soundcheck:soundcheck@127.0.0.1:55500/soundcheck_perf"
    ;;

  down)
    "$0" api-stop || true
    "${COMPOSE[@]}" stop
    storage_delete
    echo "Perf stack stopped; volume soundcheck-perf-pgdata kept (make perf-reset drops it)."
    ;;

  reset)
    echo "This deletes the perf database: docker volume soundcheck-perf-pgdata"
    docker volume inspect soundcheck-perf-pgdata --format '  {{.Name}} (created {{.CreatedAt}})' 2>/dev/null || true
    if [ "${YES:-}" != 1 ]; then
      read -r -p "Type 'delete' to go on: " answer
      [ "$answer" = delete ] || { echo "Kept."; exit 1; }
    fi
    "$0" api-stop || true
    "${COMPOSE[@]}" down -v
    storage_delete
    ;;

  api)
    if [ -n "$(api_pid)" ]; then echo "The perf API is already running (pid $(api_pid), port $PORT)."; exit 0; fi
    if ss -ltn | grep -q ":$PORT "; then echo "Port $PORT is taken by another process."; exit 1; fi
    jar=$(ls "$ROOT"/backend/build/libs/*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -1 || true)
    if [ -z "$jar" ] || [ -n "${REBUILD:-}" ]; then
      echo "Building the jar (under the heavy lock)…"
      flock "$LOCK" systemd-run --user --scope -q -p MemoryMax=3G \
        "$ROOT/backend/gradlew" -p "$ROOT/backend" --no-daemon "-Dorg.gradle.jvmargs=-Xmx1g" bootJar
      jar=$(ls "$ROOT"/backend/build/libs/*.jar | grep -v -- '-plain\.jar$' | head -1)
    fi
    # Run from backend/, like `make api`: the JWT key path (../.secrets) and fixtures (../fixtures) are
    # relative to it. Only PERF_* variables configure it (application-perf.yaml); .env's are cleared.
    cd "$ROOT/backend"
    env -u DB_URL -u DB_USER -u DB_PASSWORD -u S3_ENDPOINT -u S3_BUCKET -u SQS_QUEUE_URL -u SQS_DLQ_URL \
        -u SOUNDCHECK_ROLES -u SOUNDCHECK_ADMIN_EMAIL -u SOUNDCHECK_ADMIN_PASSWORD -u SERVER_PORT \
        SPRING_PROFILES_ACTIVE=perf PERF_API_PORT="$PORT" OTLP_ENABLED="${OTLP_ENABLED:-true}" \
        nohup timeout "$API_TIMEOUT" taskset -c "$API_CPUS" \
          java "-Xmx$API_XMX" -jar "$jar" >"$STATE/api.log" 2>&1 &
    echo $! >"$STATE/launcher.pid"
    echo "Starting the perf API on $PORT (CPUs $API_CPUS, -Xmx$API_XMX, stops after $API_TIMEOUT; log $STATE/api.log)…"
    for _ in $(seq 1 120); do
      if curl -sf -m 2 "http://localhost:$PORT/actuator/health" >/dev/null 2>&1; then
        pid=$(ss -ltnp | grep ":$PORT " | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2)
        echo "$pid" >"$STATE/api.pid"
        echo "Perf API UP (pid $pid)."
        exit 0
      fi
      sleep 2
    done
    echo "The perf API didn't come up in 4 minutes; see $STATE/api.log"
    exit 1
    ;;

  api-stop)
    pid=$(api_pid)
    if [ -z "$pid" ]; then echo "No perf API running on $PORT."; exit 0; fi
    kill "$pid"
    echo "Stopped the perf API (pid $pid)."
    ;;

  psql)
    shift
    "${COMPOSE[@]}" exec -T db psql -U soundcheck -d "${PERF_DB:-soundcheck_perf}" "$@"
    ;;

  *)
    echo "usage: $0 up|down|reset|api|api-stop|psql" >&2
    exit 2
    ;;
esac
