#!/usr/bin/env bash
# Every ECS task's environment and secret names against the properties the backend
# reads (ops/aws/test/contract_check.py). Synthesises offline twice, with the defaults and with push and
# OTLP on, so the optional secrets are checked too. No credentials and no AWS calls; needs infra's
# node_modules (CI's infra job runs it after npm ci).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
cd "$ROOT/infra"
npm run -s synth -- -o "$out/default" >/dev/null
npm run -s synth -- -o "$out/flags" -c pushEnabled=true -c otlpEnabled=true \
  -c otlpEndpoint=https://otlp.example.test/otlp >/dev/null
python3 "$ROOT/ops/aws/test/contract_check.py" "$ROOT/backend/src/main/resources" "$out/default" "$out/flags"
