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
See `docs/SETUP.md` Part D. In short: `cp .env.example .env` → `make setup && make keys && make up` → `make api`, `make worker`, `make app` → `make android`.

## Data attribution
Gig data from Ticketmaster (and Skiddle when enabled); artist metadata from MusicBrainz (CC0) and Last.fm; city data from GeoNames (CC BY 4.0).
