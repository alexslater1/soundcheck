# Diagrams

Six figures of the system (the sixth is an extra). Each is 1920×1080, has at most 12 boxes and no text under 24 px, and comes as SVG and PNG, light and dark (`*-dark.*`). They are drawn from the code at `origin/main` `b8cc923` (2025-10-01).

```bash
make diagrams         # SVG from docs/diagrams/build.py, then a PNG of each through headless Chrome (one at a time)
make diagrams-check   # fails on > 12 boxes, text < 24 px, a box too narrow for its text, a source file
                      # missing at HEAD, or an SVG/PNG that is stale against build.py
```

The figures are data in `build.py` (standard library only). Text is Ubuntu Sans and Ubuntu Sans Mono, installed on the laptop (`fc-list`). The palette is the app's (`app/src/theme/tokens.ts`). Purple marks the part a figure is about; dashed boxes are services we don't run; dashed lines are asynchronous (a queue, or after a commit); amber marks failure. `manifest.json` records which SVG each PNG was rendered from, so the check can tell a stale PNG without starting Chrome. Before each render the script waits for 3 GB of free memory; run it under the shared heavy lock when other work is running.

## The figures

### 1. System context: `01-context.svg` / `.png`
Who calls Soundcheck (fans on Android and the web, the operator's console, the demo seeder, which uses only the public API) and whom it calls: Ticketmaster daily at 05:10, 21 London venues' own pages under robots.txt with at least 5 s between requests, MusicBrainz and Last.fm for each new artist, postcodes.io for venues with no coordinates, setlist.fm (the past-gigs backfill and live setlist lookups), Firebase for push, Grafana Cloud for telemetry. Nothing outside calls in.
**Sources:** `IngestionSchedule.java:61` (the 05:10 cron), `PoliteFetcher.java`, `ArtistEnricher.java`, `VenueGeocoder.java`, `SetlistFmClient.java`, `FcmClient.java`, `application-aws.yaml` (OTLP), `lab/src/soundcheck_lab/seed/api.py`.

### 2. Containers, as deployed: `02-containers.svg` / `.png`
One jar in two roles: the API service (`api,scheduler`) behind CloudFront and an internal ALB; transcode workers (`worker` plus FFmpeg) fed by SQS through the outbox relay; the phone's video bytes go straight to the media bucket with a presigned PUT.
**Sources:** `infra/lib/edge-stack.ts`, `compute-stack.ts`, `data-stack.ts`, `shared/config/Roles.java`, `TranscodeRelay.java`, `TranscodeWorker.java`.

### 3. Upload → ready, with its failure modes: `03-upload-sequence.svg` / `.png`
Seven lifelines, ten numbered steps, seven lettered failure notes (each checked against the code): an upload cut off (Retry sends only the missing parts; no automatic resume), `complete` twice (one job, checked on AWS), the API dying after the commit (the outbox resends), a duplicate message (the claim gets no row), a worker dying or reclaimed by Spot (released, or its 15-minute lease expires), a lost lease (finalize writes nothing), a bad file or a third failure (REJECTED; FAILED, DLQ and alarm).
**Sources:** `UploadService.java`, `TranscodeRelay.java`, `OutboxResubmitter.java`, `TranscodeWorker.java` (`process` :187, `run` :217, `settle` :294, `notClaimed` :318), `TranscodeJobs.java` (`claim` :68, `finalizeReady` :140, `held` :247), `WorkerProperties.java` (lease 15 min, heartbeat 2 min, visibility 10 min, grace 90 s, 3 attempts), `app/src/features/upload/uploadMachine.ts`.

### 4. The feed pipeline and the learning loop: `04-feed-pipeline.svg` / `.png`
Twelve steps in three rows: user context → candidates (seven generators in one SQL statement) → twelve features → score `σ(bias + Σ wᵢ·fᵢ)` → MMR with the per-author cap → exploration → the 200-item session → the served-item log; then the loop: the `served_outcome` view (the one definition of "engaged"), the Python lab (logistic regression with a position prior), a DRAFT config marked simulated, the playground, and activation back into the scorer. Purple marks `FeedRanker.rank`, the pure function the live feed and the playground's preview share.
**Sources:** `HomeFeedService.java` (`build` :113), `CandidateRepository.java`, `FeedRanker.java` (`rank` :76), `Scorer.java`, `MmrReranker.java`, `ExplorationInjector.java`, `V130__feed_served_outcome.sql`, `contracts/ranking/extract.sql`, `contracts/ranking/parity-cases.json` (26 cases, tolerance 1e-6), `lab/src/soundcheck_lab/train.py`, `RankingPage.tsx`.

### 5. The AWS deployment: `05-aws.svg` / `.png`
eu-north-1 as run on 2025-10-01: one CloudFront domain in front of the web bucket, the media bucket (OAC reads `media/*` only) and, through a VPC origin, the internal ALB; API tasks and Spot workers in public subnets with public IPs but no NAT gateway, taking traffic only from the ALB; RDS in isolated subnets; SQS with its DLQ; Secrets Manager, CloudWatch alarms and Grafana Cloud beside them. The left column names the decisions behind the picture.
**Sources:** `infra/lib/network-stack.ts` (`natGateways: 0`, the S3 gateway endpoint), `data-stack.ts`, `edge-stack.ts`, `compute-stack.ts` (task sizes, `assignPublicIp`, Spot weight 4 to on-demand 1, `stopTimeout` 120 s), `observability-stack.ts`, `infra/cdk.json` (API 1–2, workers 0–2), `application-aws.yaml` (sampling 0.1).

### 6. The dual write against the outbox: `06-outbox.svg` / `.png`
Left, the rejected dual write: the status commits, the process dies before the send, and the clip waits forever (send first, and a rollback leaves a job for an upload that never completed). Right, what is built: the status and the event row in one transaction, the relay after the commit, the resubmitter for what a crash left, and the claim absorbing any duplicate.
**Sources:** `UploadService.java`, `TranscodeRelay.java`, `OutboxResubmitter.java` (every minute, rows older than one minute), `TranscodeJobs.java`.

## Notes on the figures

- Containers: the lab runs on the laptop only, so it isn't drawn; API 1–2 and workers 0–2 (`infra/cdk.json`).
- Upload: `POST /posts` and the post's publication are left out to keep seven lifelines; the post follows `MediaReady` through content's listener.
- Upload: outputs go to `media/{id}/v{attempt}/`, so a takeover writes beside the old attempt, not over it; a console retry resets the attempt count and writes under `v1/` again.
- Feed: there is no separate filtering stage: the filters are in the candidate SQL and the `feed` views; the per-author cap runs inside MMR.
- AWS: eu-north-1 and the ALB with the buckets in Edge; 1-day backups and no Container Insights (the Free plan); maxima 2 and 2; deploys from the laptop.
