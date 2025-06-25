#!/usr/bin/env bash
# `make backup`: saves the local database, every clip and the demo seed's progress to
# .localdata/backups/<UTC stamp>/, so a `make reset` (or the seed acceptance run) loses nothing.
# `make restore BACKUP=<that folder>` puts it back.
#
#   db.dump         pg_dump -Fc of the database
#   media.tar       the clips: the bucket copied to the media-snapshot volume, then out of Docker
#                   (`make reset` deletes that volume, so a copy inside Docker would not survive it)
#   seed-state.json .localdata/seed/state.json (which clips and steps the seed has done), if present
#   manifest.txt    when, from which commit, row and object counts, sizes
#
# Needs `make up` (db and localstack running). Fails, and says why, if any clip could not be copied.
# COMPOSE may be overridden (tests point it at a throwaway project).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE="${COMPOSE:-docker compose}"
DB_USER="${DB_USER:-soundcheck}"
DB_NAME="${DB_NAME:-soundcheck}"
BUCKET="${S3_BUCKET:-soundcheck-media}"
BACKUPS="${BACKUPS:-$ROOT/.localdata/backups}"

# Docker commands that read nothing get </dev/null: `compose exec` forwards stdin by default and would
# swallow keystrokes (or seed-acceptance's y/N answer).
say() { printf '\033[1m%s\033[0m\n' "$*"; }
running() { [ -n "$($COMPOSE ps -q --status running "$1" 2>/dev/null </dev/null)" ]; }

cd "$ROOT"
for service in db localstack; do
  running "$service" || { say "backup: $service is not running; start the stack with 'make up' first"; exit 1; }
done

DIR="$BACKUPS/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$DIR"
say "Backing up to $DIR"

# 1. Clips: bucket → snapshot volume (same command as `make down`), checked object by object, then
#    out of Docker as one tar file.
say "  clips: bucket → snapshot…"
if ! $COMPOSE exec -T localstack sh -c "awslocal s3 sync s3://$BUCKET /snapshot/$BUCKET --delete --only-show-errors" </dev/null; then
  say "backup: copying the clips out of LocalStack failed (see above); nothing was reset"; exit 1
fi
in_bucket=$($COMPOSE exec -T localstack sh -c "awslocal s3 ls --recursive s3://$BUCKET | wc -l" </dev/null | tr -d '[:space:]')
in_snapshot=$($COMPOSE exec -T localstack sh -c "find /snapshot/$BUCKET -type f 2>/dev/null | wc -l" </dev/null | tr -d '[:space:]')
if [ "$in_bucket" != "$in_snapshot" ]; then
  say "backup: the bucket has $in_bucket objects but only $in_snapshot were copied; nothing was reset"; exit 1
fi
say "  clips: $in_snapshot objects → media.tar…"
$COMPOSE exec -T localstack tar -C /snapshot -cf - . </dev/null >"$DIR/media.tar"

# 2. Database: custom format, checked by listing its contents.
say "  database: pg_dump…"
$COMPOSE exec -T db pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc </dev/null >"$DIR/db.dump"
entries=$($COMPOSE exec -T db pg_restore -l <"$DIR/db.dump" | grep -vc '^;' || true)
[ "$entries" -gt 0 ] || { say "backup: the database dump is empty or unreadable"; exit 1; }

# 3. The seed's progress.
if [ -f "$ROOT/.localdata/seed/state.json" ]; then
  cp "$ROOT/.localdata/seed/state.json" "$DIR/seed-state.json"
fi

# 4. What is in it.
count() { $COMPOSE exec -T db psql -U "$DB_USER" -d "$DB_NAME" -Atc "$1" 2>/dev/null </dev/null || echo "?"; }
{
  echo "backup $(basename "$DIR")"
  echo "made: $(date '+%Y-%m-%d %H:%M:%S %Z') from commit $(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo '?')"
  echo "database: $DB_NAME, $entries dump entries; users $(count 'select count(*) from identity.app_user'), gigs $(count 'select count(*) from catalog.gig'), posts $(count 'select count(*) from content.post')"
  echo "clips: $in_snapshot objects"
  echo "seed state: $([ -f "$DIR/seed-state.json" ] && echo yes || echo 'none')"
  echo "sizes:"
  du -h "$DIR"/* | sed 's/^/  /'
} >"$DIR/manifest.txt"
sed 's/^/  /' "$DIR/manifest.txt"
say "Done. To put it back: make restore BACKUP=$DIR"
