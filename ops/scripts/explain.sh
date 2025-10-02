#!/usr/bin/env bash
# EXPLAIN (ANALYZE, BUFFERS) one named hot-path query against the perf database.
# Usage: ops/scripts/explain.sh <name> [<name>…]   (names: ops/perf/queries/*.sql, or `all`)
# Writes docs/perf/runs/<UTC>-explain-<name>-<profile>.txt: the environment, the query file's header (where its
# SQL and binds came from) and the plan. Each file PREPAREs the statement exactly as the API sends it ($n
# binds) and EXECUTEs it with real binds; writes (events_insert, rate_limit_check) run inside a transaction
# that is rolled back. EXPLAIN ANALYZE executes the query, so this is never pointed at the local stack's database.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
QUERIES="$ROOT/ops/perf/queries"
DB_CONTAINER=soundcheck-perf-db-1
OUT_DIR="$ROOT/docs/perf/runs"

[ $# -gt 0 ] || { echo "usage: $0 <name>…|all   (names: $(cd "$QUERIES" && ls *.sql | sed 's/\.sql$//' | tr '\n' ' '))" >&2; exit 2; }
names=("$@")
[ "${names[0]}" = all ] && mapfile -t names < <(cd "$QUERIES" && ls *.sql | sed 's/\.sql$//')

psql_perf() { docker exec -i "$DB_CONTAINER" psql -U soundcheck -d soundcheck_perf -X -q -P pager=off "$@"; }

users=$(psql_perf -At -c "select count(*) from identity.app_user where email like '%@perf.soundcheck.test'")
case "$users" in 5000) profile=small ;; 100000) profile=medium ;; 1000000) profile=large ;; *) profile="custom-$users-users" ;; esac
mkdir -p "$OUT_DIR"

for name in "${names[@]}"; do
  file="$QUERIES/$name.sql"
  [ -f "$file" ] || { echo "explain.sh: no query $file" >&2; exit 2; }
  check=$(sed -n 's/^-- check: //p' "$file" | head -1)
  if [ -n "$check" ] && [ "$(psql_perf -At -c "$check")" != t ]; then
    echo "explain.sh: $name's binds are not in this database (check failed: $check)." >&2
    echo "  They come from another seed-scale load; recapture them." >&2
    exit 1
  fi
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  out="$OUT_DIR/$stamp-explain-$name-$profile.txt"
  {
    echo "# explain.sh $name on $profile"
    echo "utc:     $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "commit:  $(git -C "$ROOT" rev-parse HEAD)"
    echo "uptime:  $(uptime)"
    echo "power:   online=$(cat /sys/class/power_supply/*/online 2>/dev/null | tr '\n' ' ')(1 = on mains)"
    echo "postgres: $(docker inspect "$DB_CONTAINER" --format '{{.Config.Image}} cpuset={{.HostConfig.CpusetCpus}} mem={{.HostConfig.Memory}}')"
    echo "query:   ops/perf/queries/$name.sql"
    grep '^-- ' "$file" | grep -v '^-- check:'
    echo
    # Run twice: the first execution warms the cache, the second is the plan recorded (both are printed).
    echo "## first execution (cold or partly warm cache)"
    psql_perf <"$file" 2>&1 | grep -v -E '^(BEGIN|ROLLBACK|PREPARE)$'
    echo
    echo "## second execution (warm cache)"
    psql_perf <"$file" 2>&1 | grep -v -E '^(BEGIN|ROLLBACK|PREPARE)$'
  } >"$out"
  if grep -q -E '^(ERROR|psql:)' "$out"; then
    echo "explain.sh: $name failed; see $out" >&2
    exit 1
  fi
  echo "$name: ${out#"$ROOT"/} ($(grep -m1 -o 'Execution Time: [0-9.]* ms' <(sed -n '/## second execution/,$p' "$out")))"
done
