# docs/perf
Performance evidence: datasets, run files, EXPLAIN plans, a JFR summary and `REPORT.md`. Every number here comes
from a file in `runs/` that names its commit, UTC time and environment. All of it was measured on a laptop.

- `REPORT.md`: the results and what they show.
- `machine.md`: the machine, versions, CPU split and the conditions before each batch of runs.
- `experiments.md`: the experiments run in this campaign.
- `login-pool.txt` (medium) and `login-pool-small.txt`: the 500 handles k6 signs in as (written by `seed-scale`).
- `runs/`: one file per recorded run.
  - `<UTC>-seed-scale-<profile>.txt`: a dataset load (`ops/perf/seed-run.sh`).
  - `<UTC>-<scenario>-<profile>.json`: a k6 run (`make load` / `ops/perf/run.sh`); `…-pg-top.txt` beside it holds
    the top statements of that run.
  - `<UTC>-explain-<name>-<profile>.txt`: a plan from `ops/scripts/explain.sh`.
  - `<UTC>-pg-top-medium.txt`: the top statements once the database was idle after the last run.
  - `<UTC>-jfr-feed-medium.txt`: `jfr summary` and `jfr view` of a recording taken during a feed run.
- `screens/`: Grafana screenshots of the runs' time windows.

## Running a load test
```bash
export PERF_STANDALONE=1             # the perf stack's own LGTM (Grafana :3010) and LocalStack (:4576)
make perf-up                         # Postgres on 127.0.0.1:55500 (compose project soundcheck-perf)
PERF_API_PORT=8200 make perf-api     # the jar with profile perf, pinned to CPUs 0,2
PERF_API=http://localhost:8200 ops/perf/seed-run.sh medium   # the dataset, recorded in runs/
make load SCENARIO=feed PROFILE=medium RATE_SCALE=1.0 DURATION=5m PERF_API_PORT=8200
```
Scenarios (`load/`): `feed`, `following`, `gigs`, `events`, `upload`, `mixed`. `run.sh` refuses a dirty tree and
the local stack's ports, waits for a quiet machine (`MIN_MEM_GB` free, 1-minute load under 2.0), takes the heavy
lock, resets `pg_stat_statements` and writes the run file. `SMOKE=1` labels a script check, `WARMUP=1` a warm-up
run (kept, never measured), `NOISY=1` a run taken while something else was busy. Each run file also holds the CPU
clock sampled every 10 s (`environment.cpu_mhz`). Grafana's **Soundcheck** folder has the API, Feed, Media and
Ingestion dashboards; pick `env=perf`.

A run file's verdicts are k6 thresholds built from the targets in `load/lib/config.js`, over the hold phase only.
`"smoke": true` runs checked the scripts at a tenth of the rates on **small**: they are not measurements of the
targets, which are set for **medium**.

The run files in `runs/` have no `environment.top_cpu_before` field: `run.sh` writes the names of the busiest
processes there, and they were removed before committing. `machine.md` records the busiest processes before each
batch instead.

## The named queries (`ops/scripts/explain.sh`)
`ops/scripts/explain.sh all` (or one name) runs each `ops/perf/queries/<name>.sql` under
`EXPLAIN (ANALYZE, BUFFERS)` twice and keeps both plans: `feed_candidates`, `following_page`, `gigs_near`,
`post_hydrate`, `post_engager_counts`, `post_stat_features`, `profile_post_count`, `events_insert` (rolled back),
`rate_limit_check` (rolled back) and `rollup_end_of_batch` (rolled back). Each file PREPAREs the statement exactly
as the API sends it and EXECUTEs it with binds from one real request on the medium load.

### Recapturing the binds
The binds are ids from one seed-scale load; after another load `explain.sh` stops with "check failed". To recapture:
1. `docker exec soundcheck-perf-db-1 psql -U soundcheck -d soundcheck_perf -c "alter role soundcheck in database soundcheck_perf set auto_explain.log_min_duration = 0"`, then restart the perf API (role settings apply to new connections).
2. Make one request of each kind as a pool user (For You, Following, gigs near a city, events, a profile).
3. Reset the setting (`… reset auto_explain.log_min_duration`) and restart the perf API again, or every later run logs every plan.
4. `docker logs --since <time> soundcheck-perf-db-1`: each statement's `Query Text` and `Query Parameters`. Paste them into the query files and update their headers.

## Top statements (`ops/scripts/pg-top.sh`)
`ops/scripts/pg-top.sh [dsn]`: the 20 statements with the most total time since the last reset, with calls,
mean, total, rows, buffer hits and reads and the hit ratio. It refuses port 5432 (the local stack's database).

## The feed profile (JFR)
```bash
jcmd <perf API pid> JFR.start name=feed duration=120s settings=profile filename=feed.jfr
jfr summary feed.jfr; jfr view hot-methods feed.jfr; jfr view allocation-by-class feed.jfr
```
The `.jfr` recording itself is not kept in the repo, only its text summary.
