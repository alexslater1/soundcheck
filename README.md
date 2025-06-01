# Soundcheck

Discover live music near you, share concert clips, and get a feed that learns what you care about.

> **Status:** the local stack, backend skeleton, app shell, contract pipeline, lab and infra stubs and CI are built. Screenshots, the live URL and measured numbers come later.

Soundcheck is a music social app (Java/Spring Boot + PostgreSQL API, React Native client, S3 video storage), built to scale.

## What's interesting here
- **Scalable media pipeline** — direct-to-S3 resumable uploads, transactional outbox → SQS → FFmpeg workers on Fargate Spot scaling with queue depth, HLS adaptive streaming through CloudFront.
- **Personalised ranking you can see inside** — candidate generation, a transparent logistic scorer with per-item explanations, diversity re-ranking, exploration, and a live tuning console.
- **Offline learning loop** — serve-time feature logging, a Python lab that trains and evaluates new weights, and a human-approved publish/activate flow; Java/Python parity tests.
- **Real data integration** — UK gigs from Ticketmaster (and Skiddle), enriched with MusicBrainz and Last.fm, with entity resolution across sources.
- **Measured performance** — million-row datasets, k6 load tests and an EXPLAIN-driven before/after report.
- **Infrastructure as code** — AWS CDK, OIDC deploys, alarms, budgets.

## Repository guide
| Path | Contents |
|---|---|
| `docs/SETUP.md` | Accounts, keys and Ubuntu toolchain |
| `backend/` | Spring Boot 3.5 / Java 21 modular monolith (Spring Modulith, jOOQ, Flyway); roles `api`, `scheduler`, `worker` |
| `app/` | Expo SDK 53 universal app (Android + web + admin console), TypeScript, Expo Router, TanStack Query, orval client |
| `lab/` | Python 3.13 ranking lab (uv, typer CLI `soundcheck-lab`) |
| `infra/` | AWS CDK v2 (TypeScript) stacks with cdk-nag |
| `contracts/` | `openapi.json` snapshot and `ranking/feature-schema.json`, shared by backend, app and lab |
| `ops/` | Local stack: Caddy edge, LocalStack init hook, Grafana provisioning |
| `load/` · `fixtures/` | k6 scenarios · recorded API pages and synthetic clips |
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
make status         # what is running: containers, api/worker/expo, build daemons, phone forwards
make help           # every target with a one-line description
```

## Stopping
1. **Ctrl+C** in each terminal: `make api`, `make worker`, `make app` (or `make android`, which stays running as the bundler).
2. **`make down`** first copies the clips out of LocalStack (its free edition forgets the bucket when it stops; the next `make up` puts them back), then stops the containers (data is kept), stops the Gradle and Kotlin build daemons, and finally lists anything still running, with how to stop it. It should end with `Nothing left running.`

That is all. The build daemons are why step 2 does more than `docker compose down`: Gradle keeps a background process after every build (backend and Android), and the Android build adds a Kotlin compile daemon. They hold up to ~2 GB each and only exit after ~3 hours idle. `make stop-daemons` stops just those, at any time. `make reset` is `make down` plus deleting the volumes (empty database, LocalStack and the saved clips). Stopping Docker any other way (a reboot, `docker compose down`) skips the copy: clips posted since the last `make down` or `make media-snapshot` are then lost, and their posts say "This clip could not be played". The adb server is left running: it is tiny and shared with Android Studio (`adb kill-server` stops it).

Then: http://localhost:8088 shows the app with "API: UP"; http://localhost:8088/api/v1/health answers directly;
Grafana at http://localhost:3000 (Explore → Tempo → `{ resource.service.name = "soundcheck" }`) shows the trace.

Checks: `make verify` (backend build + tests, app lint/typecheck/tests, orval drift, infra tests, lab checks) · `make test` · `make lab-check` · `make openapi` / `make openapi-check`.

Edge: http://localhost:8088 · API: http://localhost:8080 · Expo web: http://localhost:8081 · Grafana: http://localhost:3000 · LocalStack: http://localhost:4566

Notes: the backend needs Docker running (jOOQ code is generated from a throwaway PostGIS container at build time, and the integration tests start their own PostGIS and LocalStack containers). The Android Gradle build needs JDK 17 (`sdk install java 17.0.x-tem`; JDK 24+ breaks AGP's CMake step) while the backend uses JDK 21 — `make android` picks the SDKMAN JDK 17 automatically when present. Targets a later phase implements print `not yet implemented (Pxx)` and exit 1 until then.

## Data attribution
Gig data from Ticketmaster (and Skiddle when enabled); artist metadata from MusicBrainz (CC0) and Last.fm; city data from GeoNames (CC BY 4.0).
