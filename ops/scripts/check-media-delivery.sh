#!/usr/bin/env bash
# Media delivery check: a READY clip is served through the local edge the way CloudFront will serve it.
#   ops/scripts/check-media-delivery.sh <mediaId> [version]      (needs `make up`; no API needed)
# Exits non-zero on the first thing that is wrong.
set -euo pipefail
ID=${1:?usage: check-media-delivery.sh <mediaId> [version]}
BASE=${PUBLIC_BASE_URL:-http://localhost:8088}/media/$ID/v${2:-1}
IMMUTABLE='public, max-age=31536000, immutable'

expect() { # url, content type
  local headers status type cache
  headers=$(curl -sS -o /dev/null -D - "$1" | tr -d '\r')
  status=$(echo "$headers" | head -1 | cut -d' ' -f2)
  [ "$status" = 200 ] || { echo "FAIL $1: status $status"; exit 1; }
  type=$(echo "$headers" | grep -i '^content-type:' | cut -d' ' -f2- || true)
  cache=$(echo "$headers" | grep -i '^cache-control:' | cut -d' ' -f2- || true)
  [ "$type" = "$2" ] || { echo "FAIL $1: content type '$type', expected '$2'"; exit 1; }
  [ "$cache" = "$IMMUTABLE" ] || { echo "FAIL $1: cache-control '$cache'"; exit 1; }
  echo "ok   $1  ($type)"
}

expect "$BASE/master.m3u8" application/vnd.apple.mpegurl
master=$(curl -fsS "$BASE/master.m3u8")
echo "$master" | grep -q '^#EXTM3U' || { echo "FAIL: not a playlist"; exit 1; }
for playlist in $(echo "$master" | grep -v '^#' | grep .); do
  expect "$BASE/$playlist" application/vnd.apple.mpegurl
  folder=$(dirname "$playlist"); variant=$(curl -fsS "$BASE/$playlist")
  expect "$BASE/$folder/$(echo "$variant" | sed -n 's/^#EXT-X-MAP:URI="\(.*\)"/\1/p')" video/mp4
  for segment in $(echo "$variant" | grep '\.m4s$'); do
    expect "$BASE/$folder/$segment" video/mp4
  done
done
expect "$BASE/poster.jpg" image/jpeg
expect "$BASE/thumb.webp" image/webp

range=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Range: bytes=0-99' "$BASE/poster.jpg")
[ "$range" = 206 ] || { echo "FAIL: range request answered $range"; exit 1; }
write=$(curl -sS -o /dev/null -w '%{http_code}' -X PUT --data x "$BASE/should-not-exist.txt")
[ "$write" = 405 ] || { echo "FAIL: PUT through the edge answered $write, expected 405"; exit 1; }
delete=$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE "$BASE/master.m3u8")
[ "$delete" = 405 ] || { echo "FAIL: DELETE through the edge answered $delete, expected 405"; exit 1; }
echo "ok   range request 206; PUT and DELETE refused with 405"

# Only media/ is served. The originals live under uploads/ in the same bucket: a path that climbs out
# of /media/ (plain or percent-encoded) must not be answered by the bucket, and neither must a listing.
ROOT=${PUBLIC_BASE_URL:-http://localhost:8088}
for path in "/media/../uploads/" "/media/%2e%2e/uploads/" "/media/..%2fuploads/" "/media/?list-type=2"; do
  body=$(curl -sS --path-as-is "$ROOT$path" || true)
  if echo "$body" | grep -q '<ListBucketResult\|<Contents>'; then
    echo "FAIL $path: the bucket answered with a listing"; exit 1
  fi
done
echo "ok   no listing, and no way out of /media/ to the originals"
