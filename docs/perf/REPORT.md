# Soundcheck performance report

**Measured on a laptop** (an i7-12700H, with Postgres, the API and k6 pinned to separate cores), not on AWS. The
AWS API task is 0.5 vCPU / 1 GB on RDS `t4g.micro`, so these numbers are not AWS numbers. Every number below links
to the run file it comes from. A target that was missed is reported as missed.

Sections: [Environment](#environment) · [Dataset](#dataset) · [Method](#method) · [Results](#results-medium) ·
[Smoke runs](#smoke-runs-small) · [Top statements and plans](#top-statements-and-plans) ·
[Feed profile](#feed-profile-jfr) · [Grafana](#grafana) · [What the numbers say](#what-the-numbers-say) ·
[Reproducing a row](#reproducing-a-row)

## Environment
The machine, versions, CPU split and the conditions before each batch: [machine.md](machine.md). In short:
- Intel i7-12700H (6 P-cores with 2 threads each, 8 E-cores), 15.3 GiB RAM, Linux 7.0.0-34, Docker 29.8.2,
  PostgreSQL 17.5 (PostGIS 3.5.2), OpenJDK 21.0.12.1, k6 v1.0.0; on mains power for every run (each run file
  records it).
- Postgres: CPUs 4, 6, 8, 10 (4-CPU quota, 4 GiB, `shared_buffers` 1,280 MB). API: CPUs 0, 2, `-Xmx1g`, Hikari
  pool 10, profile `perf` (rate limits raised but still checked). k6: the 8 E-cores (12–19), at most 600 VUs per
  executor, in a 3 GiB memory scope.
- The API sends telemetry to the perf stack's own LGTM (Grafana :3010) and its S3/SQS calls to its own LocalStack
  (:4576).
- Memory was tight: the desktop's browser held about 11 GB and swap was full, so 3.6–5.7 GiB was available at
  the start of each run (`MIN_MEM_GB=3` instead of `run.sh`'s default 5). No run is marked noisy: each started
  with a 1-minute load under 2.0 and an idle database.

## Dataset
**medium**, synthetic, seed 42, loaded by `ops/perf/seed-run.sh medium`
([run](runs/20261013T022108Z-seed-scale-medium.txt)): 100,000 users, 1,000,000 posts, 50,000 gigs, 2,000,000 user
follows, 8,000,000 likes and 10,000,000 interaction events, every count exact. The load took 597 s and the
database is 9.33 GB. **small** ([run](runs/20261013T014930Z-seed-scale-small.txt)): 5,000 users, 50,000 posts,
8,000 gigs, 100,000 follows; 36.7 s, 0.71 GB. Each run file's `rows` records the counts at the time of the run.

## Method
- **Scenarios** (`load/`): `feed` (a visit is For You's first page, then 3 later pages with 1 s think time),
  `following`, `gigs` (within 25 km of a city, next 30 days), `events` (a batch of 20), `upload` (create, a 2 MB
  PUT to the presigned URL, complete), `mixed` (40 % feed, 20 % events, 15 % gigs, 10 % profile, 10 % post,
  5 % like or comment; 250 operations a second).
- **Open model.** Arrival-rate executors start requests at the target rate whatever the latency, so a slow API
  shows as latency and `dropped_iterations` (visits k6 could not start because every VU was busy), never as a
  quietly lower rate. The 500 logins happen in `setup()`, before the clock starts.
- **Each run**: a 1-minute ramp to the target rate, then a **5-minute hold** at it. Only the hold is judged (k6
  thresholds built from the targets in `load/lib/config.js`). Requests time out after 120 s.
- **Warm-up.** After the API started, one unmeasured run of each scenario (30 s ramp + 30 s hold) warmed the JVM
  and Postgres's caches. Those files are kept, marked `"warmup": true`, and never appear in a table. The scenarios
  then ran back to back: feed, following, gigs, events, upload, mixed, feed, gigs. Before each run `run.sh` waited
  for a 1-minute load average under 2.0, and the next run started only once the database had no active
  statements left.
- **Run-to-run spread**: `feed` and `gigs` were run twice, 43 and 36 minutes apart.
- **DB time** is the total execution time of the run's top 20 statements in `pg_stat_statements` (reset at the
  start of each run; `setup()`'s 500 logins included). **Hit ratio** is their shared-buffer hits over hits plus
  reads. Both come from the `…-pg-top.txt` file beside each run file, written as k6 ends.
- **Mixed** was attempted four times: in three, k6 itself was OOM-killed inside its 3 GiB scope (600 VUs in each
  executor, no swap left), and `run.sh` records nothing for a killed run. The one complete run is below.

## Results (medium)
Measured 2026-10-13 02:53–03:59 UTC at commit `cfce53c`. Medium, on a laptop. Latencies are the hold phase's, in ms.

| Endpoint (target rate, p95) | Run | Achieved rate/s | p50 | p95 | p99 | Errors | DB time (top 20) | Hit ratio | Target |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| For You first page (50/s, < 250 ms, errors < 0.1 %) | [1](runs/20261013T025347Z-feed-medium.json) | 8.56 | 30,052.87 | 57,227.48 | 60,370.06 | 47 % | [3,291.1 s](runs/20261013T025347Z-feed-medium-pg-top.txt) | 99.81 % | **missed** |
| | [2](runs/20261013T033642Z-feed-medium.json) | 7.84 | 30,074.63 | 58,381.36 | 60,040.65 | 40 % | [3,522.4 s](runs/20261013T033642Z-feed-medium-pg-top.txt) | 99.79 % | **missed** |
| For You later page (150/s, < 40 ms) | [1](runs/20261013T025347Z-feed-medium.json) | 9.99 | 29,823.47 | 59,629.79 | 59,901.39 | 29 % | (same run) | | **missed** |
| | [2](runs/20261013T033642Z-feed-medium.json) | 9.32 | 30,006.32 | 57,317.02 | 58,446.53 | 16 % | (same run) | | **missed** |
| Following (100/s, < 60 ms) | [1](runs/20261013T030319Z-following-medium.json) | 100 | 8.05 | 15.97 | 23.86 | 0 % | [28.4 s](runs/20261013T030319Z-following-medium-pg-top.txt) | 99.95 % | met |
| Gigs near + date (100/s, < 80 ms) | [1](runs/20261013T030931Z-gigs-medium.json) | 100 | 7 | 12.09 | 14.31 | 0 % | [50.6 s](runs/20261013T030931Z-gigs-medium-pg-top.txt) | 100.00 % | met |
| | [2](runs/20261013T034527Z-gigs-medium.json) | 100 | 7.14 | 12.28 | 14.5 | 0 % | [50.9 s](runs/20261013T034527Z-gigs-medium-pg-top.txt) | 100.00 % | met |
| Events, 20 per batch (100/s, < 40 ms) | [1](runs/20261013T031542Z-events-medium.json) | 100 | 7.33 | 9.01 | 15.52 | 0 % | [17.3 s](runs/20261013T031542Z-events-medium-pg-top.txt) | 99.92 % | met |
| Upload create (20/s, < 100 ms) | [1](runs/20261013T032155Z-upload-medium.json) | 19.98 | 10.95 | 21.43 | 265.36 | 0 % | [9.0 s](runs/20261013T032155Z-upload-medium-pg-top.txt) | 99.47 % | met |
| Mixed, 250 ops/s: For You first page | [1](runs/20261013T035248Z-mixed-medium.json) | 4.54 | 22,627.85 | 54,654.46 | 55,938.49 | 14 % | [3,400.5 s](runs/20261013T035248Z-mixed-medium-pg-top.txt) | 99.77 % | **missed** |
| Mixed: For You later page | [1](runs/20261013T035248Z-mixed-medium.json) | 8.46 | 15,651.81 | 28,690.58 | 32,144.91 | 2 % | (same run) | | **missed** |
| Mixed: events | [1](runs/20261013T035248Z-mixed-medium.json) | 6.13 | 16,136.38 | 34,406.59 | 54,618.65 | 6 % | (same run) | | **missed** |
| Mixed: gigs near | [1](runs/20261013T035248Z-mixed-medium.json) | 4.82 | 16,018.63 | 40,375.02 | 54,988.16 | 6 % | (same run) | | **missed** |
| Mixed: profile / post / like or comment (no target) | [1](runs/20261013T035248Z-mixed-medium.json) | 2.94 / 3.25 / 1.64 | 16,508.45 / 16,200.51 / 16,129.12 | 46,967.89 / 39,594.89 / 51,313.33 | 54,983.17 / 54,619.05 / 54,921 | 7 % / 6 % / 7 % | (same run) | | — |

k6 dropped 13,231 and 13,457 visits in the two feed runs (1,200 VUs busy) and 71,271 operations in the mixed run;
upload dropped 6.

**Targets: 4 of 6 met** (Following, gigs near, events, upload create); For You's first and later pages are missed
at 50 visits a second, and in the mixed run every endpoint queues behind For You.

**Run-to-run spread** (the same scenario at the same rate): gigs' p95 12.09 vs 12.28 ms (0.19 ms, 1.6 %;
[1](runs/20261013T030931Z-gigs-medium.json), [2](runs/20261013T034527Z-gigs-medium.json), 36 minutes apart).
For You's first-page p95 57,227 vs 58,381 ms (1,154 ms, 2.0 %; [1](runs/20261013T025347Z-feed-medium.json),
[2](runs/20261013T033642Z-feed-medium.json), 43 minutes apart), the spread of a saturated system, not of a
working endpoint.

## Smoke runs (small)
Script checks at a tenth of the target rates (`SMOKE=1`, `RATE_SCALE=0.1`), a 1-minute ramp and a 1-minute hold,
at commit `8db165b`. They are not measurements of the targets, which are set for medium, but every check passed
and every endpoint answered within its target's latency at that rate:

| Scenario | Run | Endpoint: achieved rate/s, p95 ms |
|---|---|---|
| gigs | [run](runs/20261013T015024Z-gigs-small.json) | gigs near: 10.02, 22.06 |
| following | [run](runs/20261013T015246Z-following-small.json) | following: 10.02, 32.74 |
| events | [run](runs/20261013T020057Z-events-small.json) | events: 10.02, 13.68 |
| feed | [run](runs/20261013T021342Z-feed-small.json) | first page: 5, 134.1; later page: 15, 23 |
| upload | [run](runs/20261013T021604Z-upload-small.json) | upload create: 2.02, 21.5 |
| mixed | [run](runs/20261013T021815Z-mixed-small.json) | first page 2.7, 86.64; later page 7.2, 28.57; events 5.23, 12.33; gigs 3.65, 18.32 |

Plans on small: [gigs_near](runs/20261013T022030Z-explain-gigs_near-small.txt) and
[rate_limit_check](runs/20261013T022031Z-explain-rate_limit_check-small.txt). The other named queries carry ids
from the medium load (see [README](README.md#recapturing-the-binds)).

## Top statements and plans
In both feed runs and the mixed run, For You's candidate statement (`CandidateRepository.candidates`, one
`UNION ALL` of the generators) dominates: in feed run 1 its most common text ran 1,504 times at a mean of
1,763.77 ms, 2,652.7 s of the run's 3,291.1 s DB time
([pg-top](runs/20261013T025347Z-feed-medium-pg-top.txt)); two more texts of it follow. Then come the ranking
features `post_engager_counts` (1,746 calls, mean 65.14 ms) and `post_stat_features` (1,746 calls, mean 15.69 ms).
In the mixed run the profile's post count is second (981 calls, mean 337.18 ms;
[pg-top](runs/20261013T035248Z-mixed-medium-pg-top.txt)). Hit ratios stay at 99.8 % or above for the feed: the
time is CPU on Postgres's four CPUs, not disk. The top statements once the database was idle after the last run:
[runs/20261013T035933Z-pg-top-medium.txt](runs/20261013T035933Z-pg-top-medium.txt).

**Plans** (`ops/scripts/explain.sh all`, the API's session with JIT off, warm, idle, binds from one request as
`perf_u205`; second execution):

| Statement | Execution time |
|---|---:|
| [feed_candidates](runs/20261013T035929Z-explain-feed_candidates-medium.txt) | 178.050 ms |
| [profile_post_count](runs/20261013T035932Z-explain-profile_post_count-medium.txt) | 60.758 ms |
| [rollup_end_of_batch](runs/20261013T035933Z-explain-rollup_end_of_batch-medium.txt) | 13.293 ms |
| [post_engager_counts](runs/20261013T035931Z-explain-post_engager_counts-medium.txt) | 11.709 ms |
| [gigs_near](runs/20261013T035930Z-explain-gigs_near-medium.txt) | 8.083 ms |
| [post_stat_features](runs/20261013T035932Z-explain-post_stat_features-medium.txt) | 3.302 ms |
| [following_page](runs/20261013T035930Z-explain-following_page-medium.txt) | 1.541 ms |
| [events_insert](runs/20261013T035929Z-explain-events_insert-medium.txt) | 1.159 ms |
| [post_hydrate](runs/20261013T035932Z-explain-post_hydrate-medium.txt) | 0.137 ms |
| [rate_limit_check](runs/20261013T035933Z-explain-rate_limit_check-medium.txt) | 0.087 ms |

Alone and warm, the candidate statement takes 178 ms; under 50 visits a second its mean is 1,764 ms, ten times
that, because ten pool connections run it at once on four Postgres CPUs.

## Feed profile (JFR)
A 120-second JFR recording (`settings=profile`) of the API during [feed run 1](runs/20261013T025347Z-feed-medium.json),
started 35 s after k6, so it covers the end of the ramp and roughly the first 85 s of the hold:
[runs/20261013T025424Z-jfr-feed-medium.txt](runs/20261013T025424Z-jfr-feed-medium.txt) (`jfr summary`,
`jfr view hot-methods`, `jfr view allocation-by-class`; the recording itself is not kept).
- 3,129 execution samples in 120 s on two CPUs.
- The top methods are the For You re-ranker: `ImmutableCollections$Set12.contains` 22.50 %, `MmrReranker.rerank`
  7.83 %, `HashMap.getNode` 6.94 %, `MmrReranker.authorJustShown` 3.99 % and `MmrReranker.maxSimilarity` 2.72 %.
  Diversifying a page compares each candidate with the items already chosen, so its cost grows with the
  candidate pool.
- Next is P-256 arithmetic (`IntegerPolynomialP256` `carryReduce0` 3.68 %, `carryReduce` 2.17 % and 1.73 %, `mult`
  1.95 %): verifying the ES256 access token on every request.
- Allocation: `byte[]` 44.11 % and `ThreadLocal$ThreadLocalMap$Entry[]` 36.46 % of the sampled pressure.

## Grafana
[API](screens/20261013T033642Z-feed-grafana-api.png) and [Feed](screens/20261013T033642Z-feed-grafana-feed.png)
dashboards over [feed run 2](runs/20261013T033642Z-feed-medium.json): For You's candidates stage dominates the
build time, and the feed's p95 sits flat at its ceiling through the hold. There are no screenshots of the mixed
run: a process in the perf LGTM container (1.5 GiB limit) was OOM-killed one minute into it, so Grafana has no
telemetry for the rest of that run, and the later mixed attempts that kept their telemetry lost k6 and have no run
file.

## What the numbers say
- **Following, gigs near, events and upload create keep up with their full target rates** on medium, with p95s of
  9–22 ms against targets of 40–100 ms and no errors. Their DB time is small (9.0–50.9 s over a run).
- **For You is the bottleneck.** At 50 visits a second it achieves 8.56 and 7.84 first pages a second, its p50 is
  the pool's connection timeout and 40–47 % of first pages fail. Postgres's four CPUs are busy with the candidate
  statement (3,291.1 and 3,522.4 s of DB time in runs of about six and a half minutes, against at most ten
  connections) while the API's own CPUs spend their samples in the re-ranker.
- **Mixed**: every endpoint answers (2–14 % errors) but none within its target, because the 40 % of operations
  that are For You visits hold the pool and every other request waits for a connection.
- The next things to try, from this data: a cheaper candidate statement (it is 81 % of feed run 1's DB time), a
  re-ranker whose cost does not grow with the candidate pool, and a separate pool or a concurrency limit for For You
  so the other endpoints stop queueing behind it. None of these was tried here ([experiments.md](experiments.md)).

## Reproducing a row
```bash
export PERF_STANDALONE=1
make perf-up && PERF_API_PORT=8200 make perf-api
PERF_API=http://localhost:8200 ops/perf/seed-run.sh medium
make load SCENARIO=gigs PROFILE=medium DURATION=5m PERF_API_PORT=8200
```
The run file lands in `docs/perf/runs/`; its `endpoints.<name>` holds the row's achieved rate, latencies and error
rate, and the `…-pg-top.txt` beside it the DB time and hit ratio. Expect the absolute numbers to move with the
machine: these are a laptop's.
