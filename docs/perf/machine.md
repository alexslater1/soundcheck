# The machine the campaign ran on

Recorded 2026-10-13 between 01:47 and 04:17 UTC, at commits `8db165b` (small) and `cfce53c` (medium; it changes
only the named queries' binds, so the jar is the same). Every run file also records its own `uptime`, `free -m`,
power state and CPU split; this page is the fixed part and the checks made before each batch. **Measured on a
laptop**, not on AWS: the AWS API task is 0.5 vCPU / 1 GB on RDS `t4g.micro`, so none of these numbers transfer to it.

## Hardware and software
| What | Value | From |
|---|---|---|
| CPU | 12th Gen Intel Core i7-12700H: 14 cores (6 P-cores with 2 threads each, 8 E-cores), 20 threads, max 4.6–4.7 GHz, L3 24 MiB | `lscpu`, `lscpu -e` |
| Memory | 15,711 MiB total, 4,095 MiB swap; at 02:36 UTC 3,910 MiB available and 3,897 MiB swap used | `free -m` |
| Kernel | Linux 7.0.0-34-generic (x86_64) | `uname -r` |
| Docker | 29.8.2 (server and client) | `docker version` |
| Postgres | PostgreSQL 17.5 (Debian 17.5-1.pgdg110+1), PostGIS 3.5.2, image `postgis/postgis:17-3.5` | `select version()`, `pg_extension` |
| Java | OpenJDK 21.0.12.1 (Temurin-21.0.12.1+1, LTS): runs the jar, `jcmd` and `jfr` | `java -version` |
| k6 | v1.0.0 (go1.24.2) | `k6 version` |
| Telemetry and storage | `grafana/otel-lgtm:0.11.3` (Grafana :3010, OTLP :4328, 1.5 GiB limit), `localstack/localstack:4.4.0` (:4576, 2 GiB limit) | `docker inspect` |
| Power | on mains (`/sys/class/power_supply/AC0/online` = 1) before every batch and in every run file | `cat /sys/class/power_supply/*/online` |

`lscpu -e` maps the threads to cores: CPUs 0–11 are the P-cores' threads (0/1 on core 0, 2/3 on core 1, …,
10/11 on core 5), CPUs 12–19 the E-cores (one thread each).

## The CPU split (`ops/perf/run.sh`, `ops/perf/perf.sh`, `ops/perf/compose.perf.yaml`)
| Process | CPUs | Limits |
|---|---|---|
| Perf Postgres (`soundcheck-perf-db-1`) | cpuset 4, 6, 8, 10 (one thread on each of P-cores 2–5) | 4 CPUs quota, 4 GiB memory |
| Perf API (the jar, profile `perf`) | `taskset` 0, 2 (one thread on P-cores 0 and 1) | `-Xmx1g`, Hikari pool 10, `timeout 3h` |
| k6 | `taskset` 12–19 (the 8 E-cores) | `systemd-run` scope `MemoryMax=3G`, at most 600 VUs per executor |

The sibling threads (1, 3, 5, 7, 9, 11) are not reserved: desktop processes can run on them and share a core
with the API or Postgres through hyper-threading.

## Postgres settings that matter for the plans
`shared_buffers` 1,280 MB, `effective_cache_size` 3,840 MB, `work_mem` 16 MB, `random_page_cost` 1.1, `jit` on (the
API's sessions turn it off), `max_parallel_workers_per_gather` 2, `max_connections` 100; `pg_stat_statements` and
`auto_explain` (`log_min_duration` 50 ms, `log_buffers` on, `log_analyze` off). The database is 8,895 MB after the
medium load and 9,251 MB after the last run.

## Conditions before each batch
Taken just before each batch: power, `uptime` and the five busiest processes over one second (`top`, second
sample). Besides the perf stack's own processes (Postgres, the API, LGTM's `prometheus`, `loki`, `tempo`,
`otelcol-contrib`, Docker), the busiest were a small web app in another, unrelated Docker project (one Python
process with auto-reload, 17–25 % of one CPU throughout), the desktop's file indexer (up to 98 % of one CPU for
short spells) and the browser (1–3 %).

| UTC | Before | Mains | Load (1, 5, 15 min) | MemAvailable |
|---|---|---|---|---:|
| 01:47 | stack start and jar build | yes | 0.47, 0.76, 0.94 | 5.7 GiB |
| 01:49 | seed small | yes | 1.81, 1.15, 1.06 | 6.4 GiB |
| 01:50 | small smoke runs (gigs, following, events) | yes | 2.06, 1.41, 1.16 | 5.6 GiB |
| 02:13 | small smoke runs (feed, upload, mixed) | yes | 0.28, 0.68, 0.94 | 4.8 GiB |
| 02:21 | seed medium | yes | 0.84, 1.05, 1.03 | — |
| 02:38 | medium warm-ups | yes | 5.12, 4.41, 2.61 | 4.4 GiB |
| 02:51 | medium runs 1–4 (feed, following, gigs, events) | yes | 9.64, 4.75, 3.45 | 3.9 GiB |
| 03:21 | medium runs 5–8 (upload, mixed, feed, gigs) | yes | 1.27, 1.63, 2.70 | 4.5 GiB |
| 03:51 | medium mixed, second attempt | yes | 3.34, 5.64, 6.28 | 4.3 GiB |
| 03:59 | EXPLAIN set | yes | 11.89, 10.89, 8.45 | 4.2 GiB |
| 04:01 | medium mixed, third attempt | yes | 1.71, 7.07, 7.36 | 3.4 GiB |
| 04:08 | medium mixed, fourth attempt | yes | 25.47, 15.04, 10.55 | 3.3 GiB |

A high load in this table is the tail of the previous run (For You saturates Postgres). `run.sh` then waits for a
1-minute load average under 2.0 before it starts: every run file's own `uptime` shows a 1-minute load between 0.28
and 1.89, and no run is marked noisy. The next run also started only once Postgres had no active statements left.

## Memory
The browser held about 11 GB resident and swap was full for most of the campaign, so MemAvailable was 3.3–6.4 GiB
rather than the 5 GiB `run.sh` waits for by default. The runs used `MIN_MEM_GB=3` (every run file's `free -m`
shows 3.6–5.7 GiB available at its start). Less free memory means less page cache for the 9 GB database, so more
of its reads go to the SSD than on an idle machine. It also cost runs:
- k6 was OOM-killed inside its 3 GiB scope in three of the four `mixed` attempts on medium (03:28, 04:01 and 04:08
  UTC: 600 VUs in each executor, no swap left for the scope). `run.sh` records no file for a killed run; the
  second attempt is the one in `runs/`.
- A process in the perf LGTM container (1.5 GiB limit) was OOM-killed one minute into that recorded mixed run, so
  Grafana has no telemetry for the rest of it (see `REPORT.md`, Grafana).

## Thermal throttling
`run.sh` samples `/proc/cpuinfo`'s MHz every 10 s during a run (`environment.cpu_mhz` in each run file): the
mean over CPUs 0–11 and over 12–19. These are means over all threads, idle ones included, so they show
throttling as a fall across a run, not the clock of the busy cores.
