#!/usr/bin/env bash
# Smoke test of a running Soundcheck, end to end through the public API:
#   health → register → log in → gigs near London → make a 3 s clip → upload it (presigned PUT)
#   → wait until the worker made it READY → fetch its HLS playlist → delete the account.
#
# Usage: ops/scripts/smoke.sh <base-url> [--expect-signed-headers]
#   <base-url>               http://localhost:8190 (the containers, ops/ci/images-check.sh) or the
#                            CloudFront address on AWS (eu-north-1; `make aws-smoke`).
#   --expect-signed-headers  real S3 only: a PUT whose Content-Type differs from the signed one must get
#                            403. LocalStack accepts any signature, so this stays off locally.
# Environment:
#   SMOKE_READY_TIMEOUT  seconds to wait for READY (default 180; on AWS use 600, a worker starts from zero)
#   SMOKE_CLIP_SECONDS   length of the generated clip (default 3)
#   SMOKE_MEDIA_ID_FILE  if set, the READY clip's media id is written there (one line) before the account
#                        is deleted; `make aws-smoke` then checks that clip's delivery through CloudFront
# Exit codes: 0 every step passed; 1 a step failed (the line starting FAIL says which); 2 usage or a
# missing tool. One line per step with its time. Tokens, passwords and presigned URLs are never printed.
# Needs curl, jq, ffmpeg and openssl. The throwaway account is deleted at the end, also after a failure.
set -euo pipefail

usage() {
  echo "usage: $0 <base-url> [--expect-signed-headers]" >&2
  exit 2
}

[ $# -ge 1 ] || usage
BASE="${1%/}"
shift
EXPECT_SIGNED=0
for arg in "$@"; do
  case "$arg" in
    --expect-signed-headers) EXPECT_SIGNED=1 ;;
    *) usage ;;
  esac
done
case "$BASE" in
  http://* | https://*) ;;
  *) usage ;;
esac
READY_TIMEOUT="${SMOKE_READY_TIMEOUT:-180}"
CLIP_SECONDS="${SMOKE_CLIP_SECONDS:-3}"

for tool in curl jq ffmpeg openssl; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "smoke: $tool is not installed" >&2
    exit 2
  }
done

WORK="$(mktemp -d)"
TOKEN=""
PASSWORD=""
ACCOUNT_LIVE=0

# ── output ─────────────────────────────────────────────────────────────────────────────────────────
now_ms() { date +%s%3N; }
RUN_START="$(now_ms)"
STEP=""
STEP_START=0

begin() {
  STEP="$1"
  STEP_START="$(now_ms)"
}

ok() {
  printf 'ok    %-16s %6d ms  %s\n' "$STEP" "$(($(now_ms) - STEP_START))" "${1:-}"
}

fail() {
  printf 'FAIL  %-16s %6d ms  %s\n' "$STEP" "$(($(now_ms) - STEP_START))" "$1"
  exit 1
}

# ── HTTP ───────────────────────────────────────────────────────────────────────────────────────────
# api METHOD PATH [JSON]: the status code on stdout, the body in $WORK/body. Authenticated once logged in.
api() {
  local method="$1" path="$2" body="${3:-}"
  local args=(-sS -o "$WORK/body" -w '%{http_code}' -X "$method" --max-time 30
    -H 'Accept: application/json' -H 'X-Client: android')
  if [ -n "$TOKEN" ]; then
    # Through a file, not the command line: other users can read a process's arguments.
    printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" >"$WORK/auth.curlrc"
    args+=(-K "$WORK/auth.curlrc")
  fi
  if [ -n "$body" ]; then
    args+=(-H 'Content-Type: application/json' --data-binary "$body")
  fi
  curl "${args[@]}" "$BASE$path" || true
}

# The problem's code and detail (RFC 9457), never the whole body: a body may hold a token.
problem() {
  jq -r '[.code // empty, .detail // .title // empty] | join(": ")' "$WORK/body" 2>/dev/null | head -c 200 || true
}

# expect "<codes>" <got>: <codes> is one status or several separated by spaces.
expect() {
  local want="$1" got="$2" code
  for code in $want; do
    [ "$got" != "$code" ] || return 0
  done
  fail "HTTP $got (expected $want) $(problem)"
}

# S3 answers errors as XML; only its <Code>, since the rest can echo the signature and credential.
s3_error() {
  sed -n 's:.*<Code>\(.*\)</Code>.*:\1:p' "$WORK/put-body" 2>/dev/null | head -1 || true
}

cleanup() {
  local status=$?
  if [ "$ACCOUNT_LIVE" = 1 ] && [ -n "$TOKEN" ]; then
    # A failed run still removes its account (then purged with its uploads after 7 days).
    code="$(api DELETE /api/v1/me "$(jq -cn --arg p "$PASSWORD" '{password: $p}')")"
    echo "cleanup: account delete → HTTP $code"
  fi
  rm -rf "$WORK"
  exit "$status"
}
trap cleanup EXIT

echo "smoke: $BASE (READY timeout ${READY_TIMEOUT}s, clip ${CLIP_SECONDS}s)"

# 1 ── health ─────────────────────────────────────────────────────────────────────────────────────────
begin health
code="$(api GET /api/v1/health)"
expect 200 "$code"
[ "$(jq -r .status "$WORK/body")" = UP ] || fail "status is not UP"
ok "version $(jq -r .version "$WORK/body")"

# 2 ── register a throwaway account ───────────────────────────────────────────────────────────────────
begin register
EPOCH="$(date +%s)"
EMAIL="smoke+${EPOCH}@example.com"
HANDLE="smoke_${EPOCH}"
PASSWORD="$(openssl rand -hex 16)"
code="$(api POST /api/v1/auth/register "$(jq -cn --arg e "$EMAIL" --arg h "$HANDLE" --arg p "$PASSWORD" \
  '{email: $e, handle: $h, displayName: "Smoke test", password: $p}')")"
# The controllers answer 201 here, 202 for complete and delete; contracts/openapi.json lists 200 for all
# four (springdoc doesn't see a ResponseEntity's status), so both are accepted.
expect "200 201" "$code"
TOKEN="$(jq -r .accessToken "$WORK/body")"
ACCOUNT_LIVE=1
ok "$EMAIL"

# 3 ── log in ─────────────────────────────────────────────────────────────────────────────────────────
begin login
TOKEN=""
code="$(api POST /api/v1/auth/login "$(jq -cn --arg e "$EMAIL" --arg p "$PASSWORD" '{email: $e, password: $p}')")"
expect 200 "$code"
TOKEN="$(jq -r .accessToken "$WORK/body")"
[ -n "$TOKEN" ] && [ "$TOKEN" != null ] || fail "no access token in the answer"
ok

# 4 ── gigs near London ───────────────────────────────────────────────────────────────────────────────
begin gigs
code="$(api GET '/api/v1/gigs?lat=51.5074&lng=-0.1278&radiusKm=25&limit=20')"
expect 200 "$code"
ok "$(jq '.items | length' "$WORK/body") gigs on the first page"

# 5 ── a synthetic clip (test pattern and a tone; nothing copyrighted) ─────────────────────────────
begin clip
CLIP="$WORK/clip.mp4"
ffmpeg -nostdin -loglevel error -f lavfi -i "testsrc2=size=1280x720:rate=30" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000" -t "$CLIP_SECONDS" \
  -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -shortest -movflags +faststart "$CLIP" ||
  fail "ffmpeg could not make the clip"
BYTES="$(wc -c <"$CLIP" | tr -d ' ')"
ok "$BYTES bytes"

# 6 ── upload: ticket, PUT to the presigned URL(s), complete ───────────────────────────────────────
begin upload
code="$(api POST /api/v1/media/uploads "$(jq -cn --argjson b "$BYTES" --argjson d "$((CLIP_SECONDS * 1000))" \
  '{kind: "VIDEO", contentType: "video/mp4", bytes: $b, durationMs: $d, width: 1280, height: 720}')")"
expect "200 201" "$code"
cp "$WORK/body" "$WORK/ticket.json"
MEDIA_ID="$(jq -r .mediaId "$WORK/ticket.json")"
STRATEGY="$(jq -r .strategy "$WORK/ticket.json")"
if [ "$STRATEGY" = SINGLE ]; then
  # The signed headers go back exactly as the ticket lists them (Content-Type is part of the signature).
  headers=()
  while IFS= read -r header; do headers+=(-H "$header"); done < <(jq -r '.headers // {} | to_entries[] | "\(.key): \(.value)"' "$WORK/ticket.json")
  jq -r .putUrl "$WORK/ticket.json" >"$WORK/put-url"
  put="$(curl -sS -o "$WORK/put-body" -w '%{http_code}' --max-time 120 -H 'Expect:' "${headers[@]}" \
    -T "$CLIP" --url "$(cat "$WORK/put-url")" || true)"
  [ "$put" = 200 ] || fail "PUT to storage: HTTP $put $(s3_error)"
  complete='{}'
else
  PART_SIZE="$(jq -r .partSize "$WORK/ticket.json")"
  parts='[]'
  while IFS=$'\t' read -r number url; do
    dd if="$CLIP" of="$WORK/part" bs="$PART_SIZE" skip="$((number - 1))" count=1 status=none
    put="$(curl -sS -o "$WORK/put-body" -D "$WORK/put-headers" -w '%{http_code}' --max-time 120 -H 'Expect:' \
      -T "$WORK/part" --url "$url" || true)"
    [ "$put" = 200 ] || fail "PUT of part $number: HTTP $put $(s3_error)"
    etag="$(tr -d '\r' <"$WORK/put-headers" | sed -n 's/^[Ee][Tt][Aa][Gg]: *//p' | head -1)"
    parts="$(jq -c --argjson n "$number" --arg e "$etag" '. + [{partNumber: $n, etag: $e}]' <<<"$parts")"
  done < <(jq -r '.parts | sort_by(.partNumber)[] | "\(.partNumber)\t\(.url)"' "$WORK/ticket.json")
  complete="$(jq -cn --argjson p "$parts" '{parts: $p}')"
fi
code="$(api POST "/api/v1/media/uploads/$MEDIA_ID/complete" "$complete")"
expect "200 202" "$code"
ok "$STRATEGY, media $MEDIA_ID"

# 7 ── wait for the worker ───────────────────────────────────────────────────────────────────────────
begin ready
deadline=$(($(date +%s) + READY_TIMEOUT))
status=""
while :; do
  code="$(api GET "/api/v1/media/$MEDIA_ID")"
  expect 200 "$code"
  status="$(jq -r .status "$WORK/body")"
  case "$status" in
    READY) break ;;
    REJECTED | FAILED | DELETED) fail "media is $status" ;;
  esac
  [ "$(date +%s)" -lt "$deadline" ] || fail "still $status after ${READY_TIMEOUT}s"
  sleep 2
done
HLS_URL="$(jq -r .hlsUrl "$WORK/body")"
ok "READY"

# 8 ── the playlist is served ─────────────────────────────────────────────────────────────────────────
begin playlist
code="$(curl -sS -o "$WORK/master.m3u8" -w '%{http_code}' --max-time 30 --url "$HLS_URL" || true)"
[ "$code" = 200 ] || fail "GET master.m3u8: HTTP $code"
head -1 "$WORK/master.m3u8" | grep -q '^#EXTM3U' || fail "master.m3u8 is not an HLS playlist"
ok "$(grep -c '^#EXT-X-STREAM-INF' "$WORK/master.m3u8" || true) renditions"

# 9 ── (real S3 only) the signature covers Content-Type ──────────────────────────────────────────────
if [ "$EXPECT_SIGNED" = 1 ]; then
  begin signed-headers
  code="$(api POST /api/v1/media/uploads "$(jq -cn --argjson b "$BYTES" \
    '{kind: "VIDEO", contentType: "video/mp4", bytes: $b, durationMs: 3000, width: 1280, height: 720}')")"
  expect "200 201" "$code"
  cp "$WORK/body" "$WORK/ticket2.json"
  [ "$(jq -r .strategy "$WORK/ticket2.json")" = SINGLE ] || fail "expected a single-PUT ticket"
  put="$(curl -sS -o "$WORK/put-body" -w '%{http_code}' --max-time 120 -H 'Expect:' \
    -H 'Content-Type: application/octet-stream' -T "$CLIP" --url "$(jq -r .putUrl "$WORK/ticket2.json")" || true)"
  api DELETE "/api/v1/media/uploads/$(jq -r .mediaId "$WORK/ticket2.json")" >/dev/null || true
  [ "$put" = 403 ] || fail "a PUT with another Content-Type got HTTP $put, expected 403"
  ok "403 $(s3_error)"
fi

if [ -n "${SMOKE_MEDIA_ID_FILE:-}" ]; then
  printf '%s\n' "$MEDIA_ID" >"$SMOKE_MEDIA_ID_FILE"
fi

# 10 ── delete the account ───────────────────────────────────────────────────────────────────────────
begin delete-account
code="$(api DELETE /api/v1/me "$(jq -cn --arg p "$PASSWORD" '{password: $p}')")"
expect "200 202" "$code"
ACCOUNT_LIVE=0
ok

echo "smoke: passed in $(($(now_ms) - RUN_START)) ms"
