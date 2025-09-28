#!/usr/bin/env bash
# make aws-test (and CI's infra job): shellcheck every deploy script, then the offline dry-run test.
# No credentials and no AWS calls.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
cd "$ROOT"
if command -v shellcheck >/dev/null; then
  check=(shellcheck)
else
  check=(uvx --from shellcheck-py shellcheck) # the laptop has no system shellcheck; CI's runner does
fi
"${check[@]}" ops/aws/*.sh ops/aws/test/*.sh ops/scripts/check-media-delivery.sh
[[ ! -f ops/scripts/smoke.sh ]] || "${check[@]}" ops/scripts/smoke.sh
echo "shellcheck: clean ($("${check[@]}" --version | sed -n 's/^version: //p'))"
ops/aws/test/dry-run.sh
