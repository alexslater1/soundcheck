#!/usr/bin/env bash
# `make seed-acceptance`: the demo seed's acceptance check, made safe.
#   "make reset && make up && make seed-demo completes in < 15 min and every persona's For You has
#    >= 30 items" — from an empty stack, with synthetic clips only (--no-real: the real clips are
#    hours of encoding, a separate step).
#
# Order: refuse while make dev runs → `make backup` (stops here if it fails) → ask (unless YES=1) →
# timed from here: reset (empties the database and the clips) → up → compile → Ticketmaster fixtures
# ingested → API and worker started in the background → synthetic demo clips if missing → seed-demo
# --no-real --require-feed 30 → API and worker stopped. The backup is never deleted: afterwards keep
# the fresh demo, or go back with `make restore BACKUP=<the folder printed>`.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE="${COMPOSE:-docker compose}"
GRADLE=("$ROOT/backend/gradlew" -p "$ROOT/backend")
LOGS="$ROOT/.localdata/logs"
LIMIT_S=900

say() { printf '\033[1m%s\033[0m\n' "$*"; }
now() { date +%s; }
cd "$ROOT"
mkdir -p "$LOGS"

for port in 8080 8090 8099; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    say "Port $port is in use (make dev, the API or the worker): stop it first (Ctrl+C in make dev)."; exit 1
  fi
done

say "1/7 Backup first (nothing is reset unless it succeeds)…"
BACKUPS="${BACKUPS:-$ROOT/.localdata/backups}"
"$ROOT/ops/scripts/backup.sh"
BACKUP="$(ls -1dt "$BACKUPS"/*/ 2>/dev/null | head -1)"
BACKUP="${BACKUP%/}"
[ -n "$BACKUP" ] && [ -s "$BACKUP/db.dump" ] && [ -s "$BACKUP/media.tar" ] \
  || { say "No complete backup: stopping before anything is reset."; exit 1; }

if [ "${YES:-0}" != "1" ]; then
  read -r -p "Empty the local database and clips now (backup: $BACKUP)? [y/N] " answer
  case "$answer" in y | Y | yes) ;; *) say "Nothing reset. The backup stays in $BACKUP."; exit 1 ;; esac
fi

pids=()
stop_services() {
  for pid in "${pids[@]}"; do kill -TERM -- "-$pid" 2>/dev/null || true; done
  wait 2>/dev/null || true
}
trap stop_services EXIT

start=$(now)
phase() { say "$1 (+$(($(now) - start)) s)"; }

phase "2/7 Reset: containers and volumes removed…"
$COMPOSE --profile full down --remove-orphans --volumes >/dev/null
phase "3/7 Up…"
$COMPOSE up -d --wait --wait-timeout 180 db localstack lgtm edge >/dev/null
phase "4/7 Compiling the backend, then the Ticketmaster fixtures ingested (offline)…"
"${GRADLE[@]}" -q classes >"$LOGS/acceptance-compile.log" 2>&1
INGESTION_MODE=fixtures SOUNDCHECK_ROLES=cli SERVER_PORT=8099 "${GRADLE[@]}" bootRun -q \
  --args='--spring.profiles.active=local --soundcheck.ingestion.cli=ingest --soundcheck.ingestion.cli-sources=TICKETMASTER' \
  >"$LOGS/acceptance-ingest.log" 2>&1
phase "5/7 API and worker starting (logs: .localdata/logs/acceptance-api.log, acceptance-worker.log)…"
SOUNDCHECK_ROLES=api,scheduler setsid "${GRADLE[@]}" bootRun --args='--spring.profiles.active=local' \
  >"$LOGS/acceptance-api.log" 2>&1 &
pids+=("$!")
SOUNDCHECK_ROLES=worker SERVER_PORT=8090 setsid "${GRADLE[@]}" bootRun --args='--spring.profiles.active=local' \
  >"$LOGS/acceptance-worker.log" 2>&1 &
pids+=("$!")
for url in http://localhost:8080/actuator/health http://localhost:8090/actuator/health; do
  for _ in $(seq 1 240); do curl -sf -o /dev/null "$url" && break; sleep 1; done
  curl -sf -o /dev/null "$url" || { say "Not up within 240 s: $url (see the logs)"; exit 1; }
done
if [ -z "$(ls "$ROOT"/fixtures/clips/demo/demo-*.mp4 2>/dev/null)" ]; then
  phase "6/7 Synthetic demo clips (make_clips --demo)…"
  python3 "$ROOT/ops/scripts/make_clips.py" --demo >"$LOGS/acceptance-clips.log" 2>&1
fi
phase "7/7 seed-demo --no-real --require-feed 30…"
seeded=0
(cd "$ROOT/lab" && uv run soundcheck-lab seed-demo --no-real --require-feed 30) || seeded=$?
elapsed=$(($(now) - start))

say ""
if [ "$seeded" -eq 0 ] && [ "$elapsed" -lt "$LIMIT_S" ]; then
  say "PASSED: from reset to a seeded demo in ${elapsed} s (limit ${LIMIT_S} s); every persona's For You >= 30."
  verdict=0
else
  say "FAILED: seed-demo exit ${seeded}, ${elapsed} s (limit ${LIMIT_S} s)."
  verdict=1
fi
say "The backup is in $BACKUP. Keep this fresh demo, or go back: make restore BACKUP=$BACKUP"
exit "$verdict"
