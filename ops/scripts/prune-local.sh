#!/usr/bin/env bash
# Local stack only: marks as DELETED every published post whose clip files are no longer in LocalStack.
# LocalStack (free) keeps no data across restarts; before `make down` saved a copy of the bucket,
# restarts lost the files of the clips posted until then while the database kept their posts, and
# those posts showed "This clip could not be played" in every feed. Posts are soft-deleted
# exactly as the app deletes them (status DELETED), so feeds, profiles and gig pages stop serving them.
# Run with `make prune-local` (needs `make up`).
set -euo pipefail
cd "$(dirname "$0")/../.."

compose="docker compose"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Every rendition set the bucket still has: media/<id>/v1/master.m3u8 → media/<id>/v1/
$compose exec -T localstack awslocal s3 ls s3://soundcheck-media/media/ --recursive \
  | awk '{print $4}' | grep -E '/master\.m3u8$' | sed 's#master\.m3u8$##' | sort -u > "$work/present"

$compose exec -T db psql -U soundcheck -d soundcheck -Atc \
  "select p.id || ' ' || m.output_prefix from content.post p join media.media_asset m on m.id = p.media_id
    where p.status = 'PUBLISHED' and m.output_prefix is not null" > "$work/posts"

awk 'NR == FNR { present[$1] = 1; next } !($2 in present) { print $1 }' "$work/present" "$work/posts" > "$work/gone"

published=$(wc -l < "$work/posts")
gone=$(wc -l < "$work/gone")
echo "published posts: $published · clip files missing: $gone"
if [ "$gone" -eq 0 ]; then
  exit 0
fi

ids=$(sed "s/.*/'&'/" "$work/gone" | paste -sd, -)
$compose exec -T db psql -U soundcheck -d soundcheck -v ON_ERROR_STOP=1 -Atc \
  "update content.post set status = 'DELETED', updated_at = now() where id in ($ids) and status = 'PUBLISHED'" \
  | sed 's/^/marked deleted: /'
