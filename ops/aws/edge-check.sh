#!/usr/bin/env bash
# The edge's own rules, checked from outside (aws-smoke step 3):
#   - the security headers on / (HSTS, nosniff, no framing, referrer policy);
#   - /actuator/health is not reachable through CloudFront (only /api/* reaches the API);
#   - /post/x is a single-page route: CloudFront answers with the app's index.html;
#   - an unknown /api/ path is answered by the API (problem JSON), not turned into index.html.
#   ops/aws/edge-check.sh <base-url>        (read-only GETs; EDGE_CHECK_HSTS=0 skips HSTS for plain HTTP)
set -euo pipefail
BASE=${1:?usage: edge-check.sh <base-url>}
BASE=${BASE%/}
failures=0
fail() {
  echo "FAIL $*"
  failures=$((failures + 1))
}
ok() { echo "ok   $*"; }

# status, content type and headers of one GET, with the body in $body_file
body_file=$(mktemp)
trap 'rm -f "$body_file"' EXIT
fetch() {
  headers=$(curl -sS --max-time 20 -o "$body_file" -D - "$BASE$1" | tr -d '\r')
  status=$(head -1 <<<"$headers" | cut -d' ' -f2)
  type=$(grep -i '^content-type:' <<<"$headers" | head -1 | cut -d' ' -f2- || true)
}
header() { grep -i "^$1:" <<<"$headers" | head -1 | cut -d' ' -f2- || true; }

fetch /
index_sum=$(sha256sum <"$body_file" | cut -d' ' -f1)
[[ $status == 200 && $type == text/html* ]] || fail "/ answered $status ($type), expected the app's index.html"
if [[ ${EDGE_CHECK_HSTS:-1} == 1 ]]; then
  hsts=$(header strict-transport-security)
  if [[ $hsts == *max-age=* ]]; then ok "HSTS: $hsts"; else fail "no Strict-Transport-Security on /"; fi
fi
if [[ $(header x-content-type-options) == nosniff ]]; then ok "X-Content-Type-Options: nosniff"; else fail "X-Content-Type-Options is not nosniff"; fi
frame=$(header x-frame-options)
if [[ -n $frame ]]; then ok "X-Frame-Options: $frame"; else fail "no X-Frame-Options on /"; fi
referrer=$(header referrer-policy)
if [[ -n $referrer ]]; then ok "Referrer-Policy: $referrer"; else fail "no Referrer-Policy on /"; fi

fetch /post/x
sum=$(sha256sum <"$body_file" | cut -d' ' -f1)
if [[ $status == 200 && $sum == "$index_sum" ]]; then
  ok "/post/x answered with index.html (single-page route)"
else
  fail "/post/x answered $status ($type), not the app's index.html"
fi

fetch /actuator/health
if grep -q '"status"' "$body_file" && [[ $type == application/*json* ]]; then
  fail "/actuator/health reached the API through the edge"
else
  ok "/actuator/health not reachable through the edge ($status, ${type:-no type})"
fi

# Anonymous, so the API answers 401 before it looks for the route (only listed paths are public),
# or 404 for a signed-in caller: either way problem JSON from the API, never the app's index.html.
fetch /api/v1/does-not-exist
if [[ ($status == 404 || $status == 401) && $type == application/problem+json* ]]; then
  ok "/api/v1/does-not-exist answered by the API ($status problem JSON)"
else
  fail "/api/v1/does-not-exist answered $status ($type): expected the API's problem JSON"
fi

((failures == 0)) || {
  echo "edge-check: $failures failure(s)"
  exit 1
}
echo "edge-check: ok"
