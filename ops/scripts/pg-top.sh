#!/usr/bin/env bash
# The top statements since the last pg_stat_statements_reset(): the 20 with the most total execution
# time, with calls, mean and total time, rows, shared buffer hits and reads, the hit ratio and the query's
# first 120 characters. `make load` saves its output beside every run file.
# Usage: ops/scripts/pg-top.sh [dsn]   (default: the perf database on 127.0.0.1:55500)
# Uses the host's psql when there is one, else the postgis image's (the perf stack's own image).
set -euo pipefail

DSN="${1:-postgresql://soundcheck:soundcheck@127.0.0.1:55500/soundcheck_perf}"
case "$DSN" in
  *:5432/*|*@localhost/*|*@127.0.0.1/*)
    echo "pg-top.sh: $DSN looks like the local stack's database (port 5432); refusing. Pass the perf DSN." >&2
    exit 2 ;;
esac

SQL=$(cat <<'EOF'
\pset footer off
select row_number() over (order by s.total_exec_time desc) as "#",
       s.calls,
       round(s.mean_exec_time::numeric, 2) as mean_ms,
       round(s.total_exec_time::numeric, 1) as total_ms,
       s.rows,
       s.shared_blks_hit as blks_hit,
       s.shared_blks_read as blks_read,
       case when s.shared_blks_hit + s.shared_blks_read = 0 then null
            else round(100.0 * s.shared_blks_hit / (s.shared_blks_hit + s.shared_blks_read), 1) end as hit_pct,
       left(regexp_replace(s.query, '\s+', ' ', 'g'), 120) as query
from pg_stat_statements s
join pg_database d on d.oid = s.dbid and d.datname = current_database()
order by s.total_exec_time desc
limit 20;
select 'since reset: ' || coalesce(to_char(stats_reset at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), 'never')
       as pg_stat_statements_info
from pg_stat_statements_info;
EOF
)

if command -v psql >/dev/null 2>&1; then
  psql "$DSN" -X -q -P pager=off <<<"$SQL"
else
  docker run --rm -i --network host postgis/postgis:17-3.5 psql "$DSN" -X -q -P pager=off <<<"$SQL"
fi
