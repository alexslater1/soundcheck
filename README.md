# Soundcheck

Discover live music near you, share concert clips, and get a feed that learns what you care about.

> **Status:** specification ready, implementation starting. This README is the starting point; the build fills in screenshots, the live URL and measured numbers.

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
| `backend/` · `app/` · `lab/` · `infra/` · `load/` · `ops/` | Source, tests and scripts |

## Quick start
Prerequisites: `docs/SETUP.md` Parts A–B (accounts, keys, Ubuntu toolchain).

```bash
git clone git@github.com:alexslater1/soundcheck.git && cd soundcheck
make setup          # checks the toolchain, creates .env from .env.example — fill in your keys
make up             # db, localstack, lgtm, edge (Docker Compose)
make api            # terminal 1 — API on :8080, reachable via the edge on :8088
make worker         # terminal 2 — transcode worker
make app            # terminal 3 — Expo dev server (press w for web)
make android        # dev build on the connected phone + adb reverse
make help           # every target with a one-line description
```

Edge: http://localhost:8088 · API: http://localhost:8080 · Expo web: http://localhost:8081 · Grafana: http://localhost:3000

Targets a later phase implements print `not yet implemented (Pxx)` and exit 1 until then.

## Data attribution
Gig data from Ticketmaster (and Skiddle when enabled); artist metadata from MusicBrainz (CC0) and Last.fm; city data from GeoNames (CC BY 4.0).
