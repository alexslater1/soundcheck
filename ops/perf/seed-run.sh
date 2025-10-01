#!/usr/bin/env bash
# A recorded `seed-scale` run: the environment first (UTC time, commit, uptime,
# memory, power, load), then everything seed-scale prints, into docs/perf/runs/<UTC>-seed-scale-<profile>.txt.
# Usage: ops/perf/seed-run.sh small|medium [notes…]   (take the heavy lock around it)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROFILE="${1:?usage: seed-run.sh small|medium [notes]}"
shift
NOTES="${*:-}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$ROOT/docs/perf/runs/$STAMP-seed-scale-$PROFILE.txt"
mkdir -p "$(dirname "$OUT")"

{
  echo "# seed-scale --profile $PROFILE --seed ${SEED:-42}"
  echo "utc:      $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "commit:   $(git -C "$ROOT" rev-parse HEAD)"
  echo "dirty:    $(git -C "$ROOT" status --porcelain --untracked-files=no | wc -l) tracked files changed"
  echo "uptime:   $(uptime)"
  echo "power:    online=$(cat /sys/class/power_supply/*/online 2>/dev/null | tr '\n' ' ')(1 = on mains)"
  echo "cpu:      $(lscpu | sed -n 's/^Model name: *//p'), $(nproc) threads"
  echo "postgres: $(docker inspect soundcheck-perf-db-1 --format '{{.Config.Image}} cpus={{.HostConfig.NanoCpus}} mem={{.HostConfig.Memory}}')"
  echo "notes:    $NOTES"
  echo "free -m:"
  free -m
  echo
} | tee "$OUT"

start=$(date +%s)
(cd "$ROOT/lab" && uv run soundcheck-lab seed-scale --profile "$PROFILE" --seed "${SEED:-42}") 2>&1 | tee -a "$OUT"
end=$(date +%s)
{
  echo
  echo "wall clock: $((end - start)) s"
  echo "free -m after:"
  free -m
} | tee -a "$OUT"
echo "Recorded in $OUT"
