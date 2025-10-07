#!/usr/bin/env bash
# One recorded k6 run: `make load SCENARIO=feed PROFILE=medium RATE_SCALE=1.0 DURATION=1m`.
#
#   1. refuses a dirty tree (the run file names a commit) and the local stack's ports (8080, 8088);
#   2. checks the perf API is UP and the database holds the profile asked for;
#   3. waits for MemAvailable ≥ MIN_MEM_GB and a 1-minute load average < 2.0; a run that starts
#      above it is kept but marked noisy;
#   4. takes the machine-wide heavy lock for the whole run;
#   5. records the environment (UTC time, commit, uptime, free -m, power, CPU split, row counts);
#   6. resets pg_stat_statements, runs k6 on the E-cores, and writes
#      docs/perf/runs/<UTC>-<scenario>-<profile>.json (load/lib/summary.js) and, beside it, pg-top.sh's
#      top statements for the run (…-pg-top.txt).
#
# Variables: SCENARIO (feed|following|gigs|events|upload|mixed), PROFILE (small|medium), RATE_SCALE (1.0),
# DURATION (hold, 1m), RAMP (1m), PERF_API_PORT (8200) or BASE_URL, SMOKE=1 (label a smoke run), WARMUP=1
# (label a warm-up run: kept, never measured), NOISY=1 (something else was running), NOTES (free text),
# K6_CPUS (12-19), MIN_MEM_GB (5). During the run the CPU clock is sampled every 10 s (thermal throttling
# shows as falling MHz) into the run file's environment.cpu_mhz.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCENARIO="${SCENARIO:?SCENARIO=feed|following|gigs|events|upload|mixed}"
PROFILE="${PROFILE:-medium}"
PORT="${PERF_API_PORT:-8200}"
BASE_URL="${BASE_URL:-http://127.0.0.1:$PORT}"
DB_CONTAINER=soundcheck-perf-db-1
DSN="postgresql://soundcheck:soundcheck@127.0.0.1:55500/soundcheck_perf"
LOCK="${HEAVY_LOCK:-/home/alex/Documents/Soundcheck/.localdata/locks/heavy.lock}"
K6_CPUS="${K6_CPUS:-12-19}"
MIN_MEM_GB="${MIN_MEM_GB:-5}"
SCRIPT="$ROOT/load/$SCENARIO.js"

die() { echo "run.sh: $*" >&2; exit 1; }

[ -f "$SCRIPT" ] || die "no scenario $SCRIPT"
case "$BASE_URL" in *:8080|*:8080/*|*:8088|*:8088/*) die "$BASE_URL is the local stack; load tests run only against the perf API" ;; esac
case "$PROFILE" in small) expected_users=5000 ;; medium) expected_users=100000 ;; large) expected_users=1000000 ;; *) die "PROFILE=small|medium|large" ;; esac

# 1. The run file names a commit, so the commit must be what ran.
if [ -n "$(git -C "$ROOT" status --porcelain --untracked-files=no)" ]; then
  die "the tree has uncommitted changes; commit first (a run file must name the code that ran)"
fi

# 2. The API and the dataset.
curl -sf -m 5 "$BASE_URL/actuator/health" >/dev/null || die "the perf API is not UP at $BASE_URL (make perf-api)"
psql_perf() { docker exec -i "$DB_CONTAINER" psql -U soundcheck -d soundcheck_perf -XAtq "$@"; }
users=$(psql_perf -c "select count(*) from identity.app_user where email like '%@perf.soundcheck.test'")
[ "$users" = "$expected_users" ] || die "the perf database holds $users perf users, not $PROFILE's $expected_users (make seed-scale PROFILE=$PROFILE)"

POOL="$ROOT/docs/perf/login-pool.txt"
[ "$PROFILE" = medium ] || POOL="$ROOT/docs/perf/login-pool-$PROFILE.txt"
[ -f "$POOL" ] || die "no login pool $POOL"
PASSWORD=$(sed -n 's/^PERF_PASSWORD = "\(.*\)"$/\1/p' "$ROOT/lab/src/soundcheck_lab/scale/load.py")
[ -n "$PASSWORD" ] || die "could not read PERF_PASSWORD from lab/src/soundcheck_lab/scale/load.py"

# 3. A quiet machine.
mem_gb() { awk '/MemAvailable/ { printf "%.1f", $2 / 1048576 }' /proc/meminfo; }
load1() { cut -d' ' -f1 /proc/loadavg; }
for _ in $(seq 1 30); do
  awk -v m="$(mem_gb)" -v min="$MIN_MEM_GB" -v l="$(load1)" 'BEGIN { exit !(m >= min && l < 2.0) }' && break
  echo "Waiting: MemAvailable $(mem_gb) GB (need $MIN_MEM_GB), load $(load1) (need < 2.0)…"
  sleep 60
done
awk -v m="$(mem_gb)" -v min="$MIN_MEM_GB" 'BEGIN { exit !(m >= min) }' || die "MemAvailable stayed under $MIN_MEM_GB GB for 30 minutes"
noisy=false
if [ "${NOISY:-}" = 1 ] || awk -v l="$(load1)" 'BEGIN { exit !(l >= 2.0) }'; then noisy=true; fi

# 4. The heavy lock, held until this script exits.
mkdir -p "$(dirname "$LOCK")"
exec 9>"$LOCK"
echo "Waiting for the heavy lock…"
flock 9

# 5. The environment.
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="$ROOT/docs/perf/runs"
RUN_FILE="$OUT_DIR/$STAMP-$SCENARIO-$PROFILE.json"
PG_TOP="$OUT_DIR/$STAMP-$SCENARIO-$PROFILE-pg-top.txt"
SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT
mkdir -p "$OUT_DIR"

api_pid=$(ss -ltnp 2>/dev/null | grep ":$PORT " | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2 || true)
api_cpus=$( [ -n "$api_pid" ] && taskset -cp "$api_pid" 2>/dev/null | sed 's/.*: //' || echo unknown)
api_xmx=$( [ -n "$api_pid" ] && tr '\0' ' ' <"/proc/$api_pid/cmdline" | grep -o -- '-Xmx[^ ]*' || echo unknown)
pg_cpus=$(docker inspect "$DB_CONTAINER" --format '{{.HostConfig.CpusetCpus}} (quota {{.HostConfig.NanoCpus}} nano-CPUs, mem {{.HostConfig.Memory}} bytes)')
counts=$(psql_perf -F '=' -c "select 'users', count(*) from identity.app_user
  union all select 'posts', count(*) from content.post
  union all select 'gigs', count(*) from catalog.gig
  union all select 'user_follows', count(*) from social.user_follow
  union all select 'post_likes', reltuples::bigint from pg_class where oid = 'content.post_like'::regclass
  union all select 'interaction_events', (select sum(reltuples)::bigint from pg_class c join pg_inherits i on i.inhrelid = c.oid where i.inhparent = 'signals.interaction_event'::regclass)")
# The five busiest processes over one second (top's second sample), so a build or upload is on record.
top_cpu=$(top -bn2 -d 1 -o %CPU -w 200 | awk '/^top -/ { n++ } n == 2 && $1 ~ /^[0-9]+$/ && c < 5 { printf "%s %s%%; ", $12, $9; c++ }')

jq -n \
  --arg utc "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg commit "$(git -C "$ROOT" rev-parse HEAD)" \
  --arg branch "$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)" \
  --arg profile "$PROFILE" \
  --arg base_url "$BASE_URL" \
  --argjson smoke "$( [ "${SMOKE:-}" = 1 ] && echo true || echo false )" \
  --argjson noisy "$noisy" \
  --argjson warmup "$( [ "${WARMUP:-}" = 1 ] && echo true || echo false )" \
  --arg notes "${NOTES:-}" \
  --arg uptime "$(uptime)" \
  --arg free "$(free -m)" \
  --arg power "$(cat /sys/class/power_supply/*/online 2>/dev/null | tr '\n' ' ')(1 = on mains)" \
  --arg cpu "$(lscpu | sed -n 's/^Model name: *//p'), $(nproc) threads" \
  --arg k6_version "$(k6 version 2>/dev/null | head -1)" \
  --arg k6_cpus "$K6_CPUS" \
  --arg api_cpus "$api_cpus" \
  --arg api_xmx "$api_xmx" \
  --arg pg_cpus "$pg_cpus" \
  --arg counts "$counts" \
  --arg top_cpu "$top_cpu" \
  '{
     run: { utc_start: $utc, commit: $commit, branch: $branch, profile: $profile, base_url: $base_url,
            smoke: $smoke, warmup: $warmup, noisy: $noisy, notes: $notes },
     environment: { uptime: $uptime, free_m: ($free | split("\n")), power: $power, cpu: $cpu,
                    k6: $k6_version, top_cpu_before: $top_cpu,
                    cpu_split: { postgres: $pg_cpus, api: ("taskset " + $api_cpus + ", " + $api_xmx), k6: ("taskset " + $k6_cpus) } },
     rows: ($counts | split("\n") | map(select(length > 0) | split("=") | { (.[0]): (.[1] | tonumber) }) | add)
   }' >"$SCRATCH/meta.json"

# 6. The run. pg_stat_statements starts empty, so pg-top.sh shows only this run's statements.
psql_perf -c "select pg_stat_statements_reset()" >/dev/null
echo "k6 run $SCENARIO against $BASE_URL ($PROFILE, RATE_SCALE=${RATE_SCALE:-1.0}, hold ${DURATION:-1m})…"
# The CPU clock every 10 s: the mean MHz of the API's P-cores (0-11) and of k6's E-cores (12-19).
(
  while :; do
    awk -v t="$(date -u +%H:%M:%S)" '/^processor/ { cpu = $3 } /^cpu MHz/ { if (cpu < 12) { p += $4; np++ } else { e += $4; ne++ } }
      END { printf "%s p=%.0f e=%.0f\n", t, p / np, e / ne }' /proc/cpuinfo >>"$SCRATCH/mhz.txt"
    sleep 10
  done
) &
mhz_pid=$!
set +e
BASE_URL="$BASE_URL" RUN_META="$SCRATCH/meta.json" RUN_FILE="$RUN_FILE" LOGIN_POOL="$POOL" PERF_PASSWORD="$PASSWORD" \
  systemd-run --user --scope -q -p MemoryMax=3G \
  taskset -c "$K6_CPUS" k6 run --no-usage-report --quiet "$SCRIPT"
k6_exit=$?
set -e
kill "$mhz_pid" 2>/dev/null || true
if [ -f "$RUN_FILE" ] && [ -s "$SCRATCH/mhz.txt" ]; then
  jq --rawfile mhz "$SCRATCH/mhz.txt" '.environment.cpu_mhz = { every: "10s", p_cores_0_11_and_e_cores_12_19: ($mhz | split("\n") | map(select(length > 0))) }' \
    "$RUN_FILE" >"$SCRATCH/run.json" && mv "$SCRATCH/run.json" "$RUN_FILE"
fi

{
  echo "# pg-top.sh after $SCENARIO on $PROFILE, run $STAMP (commit $(git -C "$ROOT" rev-parse --short HEAD))"
  echo "# Statements since pg_stat_statements_reset() at the start of the run: setup's logins included."
  "$ROOT/ops/scripts/pg-top.sh" "$DSN"
} >"$PG_TOP"

# 0: every target met; 99: a target missed (the run still counts). Anything else is a broken run (a script
# error, setup failing, k6 killed): it leaves no run file behind.
if [ "$k6_exit" != 0 ] && [ "$k6_exit" != 99 ]; then
  rm -f "$RUN_FILE" "$PG_TOP"
  die "k6 exited $k6_exit: no run recorded"
fi
[ -f "$RUN_FILE" ] || die "k6 wrote no run file"
echo "Run file: ${RUN_FILE#"$ROOT"/}"
echo "Top statements: ${PG_TOP#"$ROOT"/}"
# k6 exits 99 when a threshold (a target) is missed: the run still counts, the run file says which.
if [ "$k6_exit" = 99 ]; then echo "At least one target was missed (see the run file's thresholds)."; fi
