# Soundcheck

Discover live music near you, share concert clips, and get a feed that learns what you care about.

> **Status (2025-06-14):** the core is built and verified against the local stack, and demo data and polish are in progress: two rounds of phone feedback, gig stats and the act bar, London venues read from their own websites, the engagement bootstrap and the security pass are done; demo seeding is built and part-run (it stopped when LocalStack ran out of file handles). The stats, the act bar and the new icon have not run on Android yet (new native modules: `make android`). Web screenshots are in `docs/screens/web/`; measured numbers and the live URL come later.

Soundcheck is a music social app (Java/Spring Boot + PostgreSQL API, React Native client, S3 video storage), built to scale.

## What's interesting here
- **Scalable media pipeline** — direct-to-S3 resumable uploads, transactional outbox → SQS → FFmpeg workers on Fargate Spot scaling with queue depth, HLS adaptive streaming through CloudFront.
- **Personalised ranking you can see inside** — candidate generation, a transparent logistic scorer with per-item explanations, diversity re-ranking, exploration, and a live tuning console.
- **Offline learning loop** — serve-time feature logging, a Python lab that trains and evaluates new weights, and a human-approved publish/activate flow; Java/Python parity tests.
- **Real data integration** — UK gigs from Ticketmaster and from London venues' own websites (read politely: robots.txt, per-host spacing, conditional requests), enriched with MusicBrainz and Last.fm, with entity resolution and matching of the same show across sources (venue, night, shared act); setlists looked up live from setlist.fm.
- **Measured performance** — million-row datasets, k6 load tests and an EXPLAIN-driven before/after report.
- **Infrastructure as code** — AWS CDK, OIDC deploys, alarms, budgets.

## What it does today
- **Gigs** — real UK gigs from Ticketmaster (a year ahead) plus 21 grassroots London venues read from their own what's-on pages (on in the local profile only, off by default elsewhere), enriched from MusicBrainz and Last.fm and matched across sources so one show stays one gig; fans add the gigs no source lists, including past ones found on setlist.fm. A calendar with ranked artist and venue suggestions, several picks at once, near home / UK-wide / another city, saved searches; gig pages with a bar of the bill's acts, each with its own setlist and clips.
- **Clips** — record or pick, upload straight to S3 in resumable parts (compressed only on mobile data), transcoded by FFmpeg workers into an HLS ladder, played adaptively on Android and the web.
- **Social** — posts tagged with the gig and acts, likes, comments, follows, blocks, an Activity inbox driven by domain events, account deletion with a 7-day purge.
- **For You** — candidates from seven SQL generators, twelve features, a logistic score with “Why this post?” reasons, diversity re-ranking and exploration, sessions, and a serve-time feature log for the Python lab.
- **Your stats** — the acts you have seen most and the venues you go back to, counted from the gigs you marked Went (support acts included); private until you share them.
- **Demo data** — `make seed-demo` seeds 30 personas, a demo fan with the owner's real gig history and own concert clips (local only, never committed) and a scripted engagement pass, all through the public API and safe to re-run.
- **Security basics** — rate limits on sign-in, uploads, events and every signed-in user (300 requests a minute), Problem Details errors that carry a `traceId` and nothing internal, uploaded images served without their EXIF/GPS, CORS only for the local origins.

## Architecture
One Spring Boot jar in three process roles (`api`, `scheduler`, `worker`) as a modular monolith (Spring Modulith: eleven modules, boundaries checked by a test), one PostgreSQL + PostGIS database, S3 for media, SQS for transcode jobs, and an Expo app for Android and the web. The deployed shape:

```mermaid
flowchart TB
  subgraph Client
    android[Expo app<br/>Android]
    web[Expo app<br/>web build]
  end
  subgraph Edge
    cf[CloudFront<br/>one HTTPS domain]
  end
  subgraph VPC
    alb[Internal ALB]
    api[API service<br/>Spring Boot · role api+scheduler<br/>ECS Fargate, 1..N]
    worker[Transcode workers<br/>Spring Boot · role worker + FFmpeg<br/>ECS Fargate Spot, 0..N]
    rds[(PostgreSQL 17 + PostGIS<br/>RDS)]
  end
  s3web[(S3 web bucket)]
  s3media[(S3 media bucket<br/>uploads/ · media/)]
  sqs[[SQS soundcheck-transcode<br/>+ DLQ]]
  lab[Python ranking lab<br/>CLI / scheduled task]
  subgraph Sources[External sources]
    tm[Ticketmaster<br/>Discovery API]
    venues[London venues'<br/>own websites]
    enrich[MusicBrainz · Last.fm]
    sfm[setlist.fm]
  end

  android & web -->|HTTPS| cf
  cf -->|/*| s3web
  cf -->|/media/*| s3media
  cf -->|/api/* via VPC origin| alb --> api
  android & web -.->|presigned PUT| s3media
  api --> rds
  api -->|outbox relay| sqs
  sqs --> worker
  worker --> s3media
  worker --> rds
  lab -->|read features/outcomes| rds
  lab -->|publish draft config| api
  lab -.->|seed-demo: personas, gigs, clips<br/>through the public API| cf
  api -->|daily ingestion<br/>scheduler role| tm
  api -->|polite fetch: robots.txt,<br/>≥ 5 s per host, conditional GET| venues
  api -->|artist enrichment| enrich
  api -->|live setlist lookups<br/>1 h cache, nothing stored in bulk| sfm
```

Ingestion (Ticketmaster, the venue sites, enrichment) runs in the `scheduler` role, which shares the API service; setlists are looked up when a past gig's page is opened, and the venue sites are read under a compliance gate. Locally the same shape runs in Docker Compose: **Caddy** plays CloudFront (the same path routing on `localhost:8088`, and the security headers CloudFront will add), **LocalStack** plays S3 and SQS, **PostGIS** plays RDS, and **Grafana LGTM** collects the OpenTelemetry traces and metrics. AWS is not deployed yet, and the lab's train/publish loop is still to come.

## Repository guide
| Path | Contents |
|---|---|
| `docs/SETUP.md` | Accounts, keys and Ubuntu toolchain |
| `backend/` | Spring Boot 3.5 / Java 21 modular monolith (Spring Modulith, jOOQ, Flyway); roles `api`, `scheduler`, `worker` |
| `app/` | Expo SDK 53 universal app (Android + web + admin console), TypeScript, Expo Router, TanStack Query, orval client |
| `lab/` | Python 3.13 ranking lab (uv, typer CLI `soundcheck-lab`): scorer parity, and the demo seeding client (`seed-demo`) |
| `infra/` | AWS CDK v2 (TypeScript) stacks with cdk-nag |
| `contracts/` | `openapi.json` snapshot and `ranking/feature-schema.json`, shared by backend, app and lab |
| `ops/` | Local stack: Caddy edge, LocalStack init hooks (resources, clip restore), Grafana provisioning; scripts (synthetic clips, app icons, fixtures, local status, pruning lost clips) |
| `load/` · `fixtures/` | k6 scenarios · synthetic API pages and clips (the owner's real clips in `fixtures/clips/mine/` are gitignored and stay on the owner's laptop) |
| `.github/` | CI (path-filtered jobs, gitleaks), CodeQL, Dependabot |

## Quick start
Prerequisites: `docs/SETUP.md` Parts A–B (accounts, keys, Ubuntu toolchain).

```bash
git clone git@github.com:alexslater1/soundcheck.git && cd soundcheck
make setup          # checks the toolchain, creates .env from .env.example — set the API keys
make dev            # all of the below in ONE terminal: containers, API + worker in the background (logs in .localdata/logs/), Expo in front; Ctrl+C stops them
make dev ANDROID=1  # the same, but rebuilds and installs the Android dev build instead of plain Expo (after native changes)
# …or one service per terminal:
make up             # db, localstack, lgtm, edge (Docker Compose); waits until healthy
make api            # terminal 1 — API on :8080 (first run downloads Gradle deps and generates jOOQ code, ~1–2 min)
make worker         # terminal 2 — transcode worker (role worker)
make app            # terminal 3 — Expo dev server; press w for web, or open http://localhost:8088
make android        # build + install the dev build on the connected phone (needs JDK 17, see below); only after native changes
make phone          # just the phone's port forwards (adb reverse): rerun after replugging USB when the app is already installed
make apk            # a release APK for rehearsal: JS bundled in (no Metro), the local edge via adb reverse, debug-signed; ~4 GB free
adb install -r app/android/app/build/outputs/apk/release/app-release.apk   # replaces the dev build (same package); make android puts it back
make status         # what is running: containers, api/worker/expo, build daemons, phone forwards
make help           # every target with a one-line description
```

Data (needs `make up`; the seeding also `make api` and `make worker`):
```bash
make ingest                              # one gig ingestion run (INGESTION_MODE in .env; offline: make ingest INGESTION_MODE=fixtures)
make venue-preview SITES=the-lexington   # what a venue site would give (live fetch, robots.txt honoured), nothing written
make venue-coverage                      # after an ingest: per venue, its shows in the next 60 days that Ticketmaster misses
make seed-demo                           # personas, the demo fan's gigs and clips, likes, engagement (idempotent; SEED_ARGS="--personas 3 --gigs 2")
make media-snapshot                      # copy the clips out of LocalStack now (make down does it too)
make prune-local                         # mark posts whose clip files LocalStack lost as deleted
make backup                              # database, every clip and the seed's state → .localdata/backups/<stamp>/
make restore BACKUP=.localdata/backups/<stamp>   # put a backup back (stop make dev first; asks unless YES=1)
make seed-acceptance                     # backup, then seed from an empty stack in < 15 min and check every For You
```

## Stopping
1. **Ctrl+C** in each terminal: `make api`, `make worker`, `make app` (or `make android`, which stays running as the bundler).
2. **`make down`** first copies the clips out of LocalStack (its free edition forgets the bucket when it stops; the next `make up` puts them back), then stops the containers (data is kept), stops the Gradle and Kotlin build daemons, and finally lists anything still running, with how to stop it. It should end with `Nothing left running.`

That is all. The build daemons are why step 2 does more than `docker compose down`: Gradle keeps a background process after every build (backend and Android), and the Android build adds a Kotlin compile daemon. They hold up to ~2 GB each and only exit after ~3 hours idle. `make stop-daemons` stops just those, at any time. `make reset` is `make down` plus deleting the volumes (empty database, LocalStack and the saved clips). Stopping Docker any other way (a reboot, `docker compose down`) skips the copy: clips posted since the last `make down` or `make media-snapshot` are then lost, and their posts say "This clip could not be played". The adb server is left running: it is tiny and shared with Android Studio (`adb kill-server` stops it).

Then: http://localhost:8088 shows the app (Settings → About shows the API's status); http://localhost:8088/api/v1/health answers directly;
Grafana at http://localhost:3000 (Explore → Tempo → `{ resource.service.name = "soundcheck" }`) shows the trace.

Checks: `make verify` (backend build + tests, app lint/typecheck/tests, orval drift, infra tests, lab checks) · `make test` · `make lab-check` · `make e2e` (Playwright web journeys through the edge) · `make openapi` / `make openapi-check`.

Edge: http://localhost:8088 · API: http://localhost:8080 · Expo web: http://localhost:8081 · Grafana: http://localhost:3000 · LocalStack: http://localhost:4566

Notes: the backend needs Docker running (jOOQ code is generated from a throwaway PostGIS container at build time, and the integration tests start their own PostGIS and LocalStack containers). The Android Gradle build needs JDK 17 (`sdk install java 17.0.x-tem`; JDK 24+ breaks AGP's CMake step) while the backend uses JDK 21 — `make android` picks the SDKMAN JDK 17 automatically when present. Targets that are not built yet print `not yet implemented` and exit 1. The compose stack publishes Postgres, LocalStack and Grafana (anonymous Admin) on every network interface: bind them to `127.0.0.1` in `compose.yaml` before demoing on a shared network.

## Data attribution
Gig data from Ticketmaster and from London venues' own public listings pages; setlists from setlist.fm (looked up live and linked back, not stored); artist metadata from MusicBrainz (CC0) and Last.fm; city data from GeoNames (CC BY 4.0). Venues that arrive without coordinates are placed by their postcode through [postcodes.io](https://postcodes.io) (Great Britain only, under the OS OpenData licence): contains Ordnance Survey data © Crown copyright and database right 2025; contains Royal Mail data © Royal Mail copyright and database right 2025; contains National Statistics data © Crown copyright and database right 2025. Skiddle is planned once its API key arrives.
