#!/usr/bin/env bash
# `make restore BACKUP=.localdata/backups/<stamp>`: puts a `make backup` back.
#
#   database  dropped and recreated from db.dump (everything in it now is replaced)
#   clips     media.tar into the media-snapshot volume; LocalStack is restarted, and its ready hook
#             (20-restore-media.sh) copies them into the bucket with the right content types
#   seed      seed-state.json back to .localdata/seed/state.json (the old one kept as .before-restore)
#
# Stop the API and the worker first (Ctrl+C in `make dev`): they hold database connections. Needs the
# stack up (`make up`). Asks before replacing anything unless YES=1. COMPOSE may be overridden (tests).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE="${COMPOSE:-docker compose}"
DB_USER="${DB_USER:-soundcheck}"
DB_NAME="${DB_NAME:-soundcheck}"
CHECK_PORTS="${CHECK_PORTS:-8080 8090}"

say() { printf '\033[1m%s\033[0m\n' "$*"; }
running() { [ -n "$($COMPOSE ps -q --status running "$1" 2>/dev/null </dev/null)" ]; }

BACKUP="${1:-}"
[ -n "$BACKUP" ] || { say "usage: make restore BACKUP=.localdata/backups/<stamp>  (ls .localdata/backups)"; exit 1; }
case "$BACKUP" in /*) ;; *) BACKUP="$ROOT/$BACKUP" ;; esac
for f in db.dump media.tar; do
  [ -s "$BACKUP/$f" ] || { say "restore: $BACKUP/$f is missing or empty: not a complete backup"; exit 1; }
done

cd "$ROOT"
for port in $CHECK_PORTS; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    say "restore: something is listening on :$port (the API or the worker): stop make dev first"; exit 1
  fi
done
running db || { say "restore: the database is not running; start the stack with 'make up' first"; exit 1; }

[ -f "$BACKUP/manifest.txt" ] && sed 's/^/  /' "$BACKUP/manifest.txt"
if [ "${YES:-0}" != "1" ]; then
  read -r -p "Replace the local database and clips with this backup? [y/N] " answer
  case "$answer" in y | Y | yes) ;; *) say "Nothing changed."; exit 1 ;; esac
fi

say "  database: recreate $DB_NAME from db.dump…"
$COMPOSE exec -T db dropdb -U "$DB_USER" --if-exists --force "$DB_NAME" </dev/null
$COMPOSE exec -T db createdb -U "$DB_USER" "$DB_NAME" </dev/null
$COMPOSE exec -T db pg_restore -U "$DB_USER" -d "$DB_NAME" --no-owner --exit-on-error <"$BACKUP/db.dump"

say "  clips: media.tar → snapshot volume, then LocalStack restarts and reloads them…"
$COMPOSE stop localstack >/dev/null </dev/null
$COMPOSE run --rm --no-deps -T --entrypoint sh localstack \
  -c 'find /snapshot -mindepth 1 -delete && tar -C /snapshot -xf -' <"$BACKUP/media.tar"
$COMPOSE up -d --wait --wait-timeout 180 localstack </dev/null

if [ -f "$BACKUP/seed-state.json" ]; then
  mkdir -p "$ROOT/.localdata/seed"
  [ -f "$ROOT/.localdata/seed/state.json" ] && cp "$ROOT/.localdata/seed/state.json" "$ROOT/.localdata/seed/state.json.before-restore"
  cp "$BACKUP/seed-state.json" "$ROOT/.localdata/seed/state.json"
  say "  seed state restored (the previous one is .localdata/seed/state.json.before-restore)"
fi
say "Restored from $BACKUP. Start the app again with 'make dev'."
