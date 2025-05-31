#!/usr/bin/env bash
# LocalStack "ready" hook, after 10-resources.sh: puts back the media bucket that `make down` copied to
# the media-snapshot volume. LocalStack's free edition starts empty every time while the database keeps
# its posts, so without this every clip posted before a restart says "This clip could not be played".
# A copy loses object metadata, so content types and the cache header are set again by extension, the
# same way TranscodeWorker.contentType does. Runs inside the LocalStack container; idempotent.
set -euo pipefail

BUCKET="${S3_BUCKET:-soundcheck-media}"
SNAPSHOT="/snapshot/$BUCKET"
IMMUTABLE="public, max-age=31536000, immutable"

if [ ! -d "$SNAPSHOT" ] || [ -z "$(ls -A "$SNAPSHOT" 2>/dev/null)" ]; then
  echo "[restore] no media snapshot; starting with an empty bucket"
  exit 0
fi

if command -v awslocal >/dev/null 2>&1; then
  aws_() { awslocal "$@"; }
else
  aws_() { aws --endpoint-url "http://localhost:4566" "$@"; }
fi

copy() { # copy <s3-prefix> <content-type> <cache-control|-> <include-pattern>...
  local prefix="$1" type="$2" cache="$3"
  shift 3
  local args=(s3 cp "$SNAPSHOT/$prefix" "s3://$BUCKET/$prefix" --recursive --only-show-errors
    --exclude "*" --content-type "$type")
  for pattern in "$@"; do args+=(--include "$pattern"); done
  if [ "$cache" != "-" ]; then args+=(--cache-control "$cache"); fi
  [ -d "$SNAPSHOT/$prefix" ] && aws_ "${args[@]}"
  return 0
}

echo "[restore] restoring s3://$BUCKET from the media snapshot"
copy media application/vnd.apple.mpegurl "$IMMUTABLE" "*.m3u8"
copy media video/mp4 "$IMMUTABLE" "*.m4s" "*.mp4"
copy media image/jpeg "$IMMUTABLE" "*.jpg" "*.jpeg"
copy media image/webp "$IMMUTABLE" "*.webp"
copy media image/png "$IMMUTABLE" "*.png"
# Originals are private and only read by the worker; their type does not matter.
copy uploads application/octet-stream - "*"
echo "[restore] done: $(find "$SNAPSHOT" -type f | wc -l) objects"
