#!/usr/bin/env bash
# make aws-smoke: proves the deployed site works end to end, through CloudFront:
#   1 ops/scripts/smoke.sh: register → upload to S3 → transcode on a worker → READY
#     → playlist, with the signed-headers check only real S3 makes; 600 s for READY, since a worker
#     starts from zero;
#   2 the smoke clip served as CloudFront serves media (ops/scripts/check-media-delivery.sh);
#   3 the edge itself (ops/aws/edge-check.sh): security headers, single-page routes, the API's own 404s.
# It creates a throwaway account (smoke+<epoch>@example.com) and deletes it again.
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

url=$(out SoundcheckEdge PublicBaseUrl)
say "smoke test of $url"

media_id_file=$(mktemp)
trap 'rm -f "$media_id_file"' EXIT
# SMOKE_MEDIA_ID_FILE: where smoke.sh leaves the READY clip's media id.
run env SMOKE_READY_TIMEOUT=600 SMOKE_MEDIA_ID_FILE="$media_id_file" \
  "$ROOT/ops/scripts/smoke.sh" "$url" --expect-signed-headers

media_id=$(cat "$media_id_file" 2>/dev/null || true)
if dry; then
  run env PUBLIC_BASE_URL="$url" "$ROOT/ops/scripts/check-media-delivery.sh" "<smoke media id>"
elif [[ -n $media_id ]]; then
  run env PUBLIC_BASE_URL="$url" "$ROOT/ops/scripts/check-media-delivery.sh" "$media_id"
else
  say "warning: smoke.sh left no media id (SMOKE_MEDIA_ID_FILE), so the media delivery check was skipped"
fi

run "$AWS_DIR/edge-check.sh" "$url"
say "aws-smoke: ok"
