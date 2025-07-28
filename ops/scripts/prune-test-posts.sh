#!/usr/bin/env bash
# Local stack only: soft-deletes the published posts of test accounts (email ending @example.com).
# `make e2e` runs the Playwright journeys through the same edge and database as `make dev`. Each journey
# signs up a fresh @example.com account and uploads the synthetic `make clips` clips, two of which
# (clip-09 and clip-17) have no audio on purpose. Nothing removed those posts afterwards, so they sat in
# every feed and looked like "no sound". The seed personas use @seed.soundcheck.test and real
# accounts use real addresses, so neither is touched.
#
# Dry run by default: prints what it would do. `--apply` marks the posts DELETED exactly as the app does
# (feeds, profiles and gig pages then stop serving them). `make e2e` runs it with --apply afterwards.
# Run with `make prune-test-posts` (needs `make up`).
set -euo pipefail
cd "$(dirname "$0")/../.."

mode=${1:-}
if [ -n "$mode" ] && [ "$mode" != "--apply" ]; then
  echo "usage: $0 [--apply]" >&2
  exit 2
fi

psql_db() { docker compose exec -T db psql -U soundcheck -d soundcheck -v ON_ERROR_STOP=1 "$@"; }

where="status = 'PUBLISHED' and author_id in (select id from identity.app_user where email like '%@example.com')"
count=$(psql_db -Atc "select count(*) from content.post where $where")
echo "published posts by @example.com accounts: $count"
if [ "$mode" != "--apply" ]; then
  echo "dry run: nothing changed. Run with --apply (or 'make prune-test-posts APPLY=1') to soft-delete them."
  exit 0
fi
psql_db -Atc "update content.post set status = 'DELETED', updated_at = now() where $where" | sed 's/^/marked deleted: /'
