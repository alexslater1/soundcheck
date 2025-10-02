# load
k6 scenarios for the perf stack. Run them with `make load SCENARIO=<name> PROFILE=small|medium`
(`ops/perf/run.sh`), which records a run file in `docs/perf/runs/`.

| Script | What one iteration does | Rate (RATE_SCALE=1) | Target (medium) |
|---|---|---|---|
| `feed.js` | For You's first page, then 3 later pages with 1 s think time | 50 visits/s | first p95 < 250 ms, errors < 0.1 %; later p95 < 40 ms |
| `following.js` | Following's first page | 100/s | p95 < 60 ms |
| `gigs.js` | gigs within 25 km of a random city, next 30 days | 100/s | p95 < 80 ms |
| `events.js` | `POST /events` with 20 events about posts the user was served | 100/s | p95 < 40 ms |
| `upload.js` | create, PUT 2 MB to the presigned URL, complete | 20/s | create p95 < 100 ms |
| `mixed.js` | 40 % feed, 20 % events, 15 % gigs, 10 % profile, 10 % post, 5 % like or comment | 250 ops/s | the targets above, per endpoint |

`lib/config.js` holds the targets, the executors (a 1-minute ramp, then a measured hold of `DURATION`) and the
refusal of the local stack's ports; `lib/auth.js` signs the login pool in during `setup()`; `lib/summary.js` writes the run file.
