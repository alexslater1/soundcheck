# Soundcheck — every routine command. `make help` lists targets.
# Targets that aren't built yet print "not yet implemented" and exit 1.

SHELL := /bin/bash
# Each recipe runs as one script that stops at the first failing line.
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help
.ONESHELL:

COMPOSE   := docker compose
# One command, so that `VAR=value $(GRADLE) task` really passes VAR to Gradle. (As `cd backend && ./gradlew`
# the variables applied to `cd` only: `make worker` ran every role on port 8080.)
GRADLE    := $(CURDIR)/backend/gradlew -p $(CURDIR)/backend
NPM_APP   := cd $(CURDIR)/app && npm
NPM_INFRA := cd $(CURDIR)/infra && npm
UV_LAB    := cd $(CURDIR)/lab && uv

# Load .env if present so targets see the same variables as the services. A value in .env wins over the
# shell environment; to override one for a single run put it after make: `make ingest INGESTION_MODE=fixtures`.
ifneq (,$(wildcard .env))
include .env
export
endif

define not_yet
	@echo "$(1): not yet implemented"; exit 1
endef

.PHONY: help setup install keys up dev openapi-check-client down media-snapshot reset stop-daemons status api worker app phone android apk web test verify lab-check e2e screens \
	openapi openapi-check ingest venue-coverage venue-preview record-fixtures backfill-setlists backfill-past-gigs purge-past-gigs purge-fixtures prune-local prune-test-posts seed-demo seed-acceptance backup restore seed-scale perf-up perf-down perf-reset perf-api perf-api-stop simulate lab-extract lab-train lab-eval \
	lab-publish lab-loop load clips clips-check images dlq-redrive demo-preflight diagrams diagrams-check \
	aws-check aws-bootstrap aws-secrets aws-push aws-diff aws-deploy aws-smoke aws-status aws-logs aws-ingest \
	aws-backfill-past-gigs aws-seed aws-pause aws-resume aws-destroy aws-lab aws-test

help: ## List targets with one-line descriptions
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z0-9_-]+:.*## / {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# ── Environment ──────────────────────────────────────────────────────────────
setup: ## Check the toolchain and create .env from .env.example if missing
	@ok=1
	for t in docker java node npm uv ffmpeg ffprobe aws k6 gh jq make; do
	  if command -v $$t >/dev/null 2>&1; then printf "  %-8s %s\n" "$$t" "$$($$t --version 2>&1 | head -1)"; else printf "  %-8s MISSING\n" "$$t"; ok=0; fi
	done
	docker compose version >/dev/null 2>&1 || { echo "  docker compose plugin MISSING"; ok=0; }
	[ -f .env ] || { cp .env.example .env && echo "  created .env from .env.example — fill in your keys"; }
	[ $$ok = 1 ] || { echo "toolchain incomplete (see docs/SETUP.md)"; exit 1; }
	echo "toolchain OK — installing app, infra and lab dependencies"
	$(MAKE) install

install: ## Install app and infra npm packages and the lab's uv environment
	cd $(CURDIR)/app && CI=1 npm ci --no-audit --no-fund --loglevel=error
	cd $(CURDIR)/infra && CI=1 npm ci --no-audit --no-fund --loglevel=error
	cd $(CURDIR)/lab && uv sync --quiet

keys: ## Generate the ES256 JWT signing key pair into .secrets/jwt-es256.pem (private + public PEM blocks)
	mkdir -p .secrets
	if [ -f .secrets/jwt-es256.pem ]; then echo ".secrets/jwt-es256.pem exists — delete it to rotate"; exit 0; fi
	openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out .secrets/jwt-es256.pem
	openssl ec -in .secrets/jwt-es256.pem -pubout 2>/dev/null >> .secrets/jwt-es256.pem
	chmod 600 .secrets/jwt-es256.pem
	echo "wrote .secrets/jwt-es256.pem (ES256 / P-256; gitignored)"

# ── Local stack ──────────────────────────────────────────────────────────────
up: ## Start db, localstack, lgtm, edge in the background and wait until healthy
	@$(COMPOSE) up -d --wait --wait-timeout 180 db localstack lgtm edge
	$(COMPOSE) ps

# Shutting down: Ctrl+C in the api, worker and app terminals, then `make down`. Gradle leaves a daemon behind after
# every build (and the Android build a Kotlin compile daemon); they idle for ~3 h holding up to 2 GB each, which this
# 15 GB laptop feels, so down and reset stop them too and then list anything still running.
# A macro rather than `$(MAKE) stop-daemons`: with .ONESHELL a recipe containing $(MAKE) runs even under `make -n`.
define stop_daemons
	# `--stop` only reaches daemons of the wrapper's own Gradle version, hence one call per wrapper.
	$(GRADLE) --stop -q || true
	if [ -x $(CURDIR)/app/android/gradlew ]; then $(CURDIR)/app/android/gradlew -p $(CURDIR)/app/android --stop -q || true; fi
	# The brackets keep pkill from matching this recipe's own shell, whose command line contains the pattern.
	pkill -f '[K]otlinCompileDaemon' || true
endef

# LocalStack's free edition forgets the bucket when it stops, while the database keeps the posts: copy the
# bucket into the media-snapshot volume first (ops/localstack/init/ready.d/20-restore-media.sh puts it back).
# Only when LocalStack is running; a failed copy is reported but does not stop the shutdown.
define save_media
	if [ -n "$$($(COMPOSE) ps -q --status running localstack 2>/dev/null)" ]; then \
	  echo "Saving the clips (s3://soundcheck-media → media-snapshot volume)…"; \
	  $(COMPOSE) exec -T localstack sh -c 'awslocal s3 sync s3://soundcheck-media /snapshot/soundcheck-media --delete --only-show-errors && echo "  $$(find /snapshot/soundcheck-media -type f | wc -l) objects saved"' \
	    || echo "  could not save the clips; they will be missing after the next make up"; \
	fi
endef

media-snapshot: ## Copy the clips out of LocalStack now (make down does this too)
	$(save_media)

down: ## Stop the local stack (keeps volumes and a copy of the clips) and the build daemons, then list anything still running
	$(save_media)
	$(COMPOSE) --profile full down --remove-orphans
	$(stop_daemons)
	$(CURDIR)/ops/scripts/local-status.sh --leftovers

reset: ## Like down, and also drop the volumes (database, LocalStack)
	$(COMPOSE) --profile full down --remove-orphans --volumes
	$(stop_daemons)
	$(CURDIR)/ops/scripts/local-status.sh --leftovers

stop-daemons: ## Stop the Gradle (backend + Android) and Kotlin build daemons; also ends a make api/worker still running
	$(stop_daemons)

status: ## Show what is running: containers, api/worker/expo, build daemons, phone port forwards
	$(CURDIR)/ops/scripts/local-status.sh

# ── Run services ─────────────────────────────────────────────────────────────
dev: ## Everything in one terminal: make up, then api + worker in the background, then the app (ANDROID=1: rebuild and install the dev build first). Ctrl+C stops all but the containers
	$(CURDIR)/ops/scripts/dev.sh

api: ## Run the API (roles api,scheduler) from Gradle against the local stack
	SOUNDCHECK_ROLES=api,scheduler $(GRADLE) bootRun --args='--spring.profiles.active=local'

worker: ## Run the transcode worker (role worker) from Gradle; needs local FFmpeg
	SOUNDCHECK_ROLES=worker SERVER_PORT=8090 $(GRADLE) bootRun --args='--spring.profiles.active=local'

app: ## Start the Expo dev server (press w for web)
	cd $(CURDIR)/app && npx expo start

phone: ## Forward the phone's ports to the laptop (adb reverse 8088, 8081, 4566); rerun after replugging the USB cable
	adb get-state >/dev/null 2>&1 || { echo "no phone found: plug in USB, unlock it, accept the debugging prompt (check: adb devices)"; exit 1; }
	# 8088 the edge (API, web, media), 8081 the Expo bundler, 4566 LocalStack S3 (presigned upload URLs).
	for port in 8088 8081 4566; do adb reverse tcp:$$port tcp:$$port >/dev/null; done
	adb reverse --list

# `phone` is a prerequisite so the forwards exist first: `expo run:android` stays in the foreground as the bundler,
# so nothing after it in the recipe would run.
android: phone ## Build and install the Android dev build on the connected phone (runs make phone first); stays as the bundler
	# The Android Gradle build needs JDK 17 (JDK 24+ breaks AGP's CMake step); use SDKMAN's 17 when present.
	if J17=$$(ls -d $$HOME/.sdkman/candidates/java/17* 2>/dev/null | head -1) && [ -n "$$J17" ]; then export JAVA_HOME="$$J17"; fi
	# Four Gradle workers, not one per core: 20 parallel native (CMake) builds ran the 15 GB laptop out of
	# memory with the API and worker up (2025-08-30). expo run:android takes no Gradle flags; GRADLE_OPTS
	# reaches Gradle ("Using 4 worker leases" in --debug).
	export GRADLE_OPTS="$${GRADLE_OPTS:-} -Dorg.gradle.workers.max=4"
	cd $(CURDIR)/app && npx expo run:android --device

# A release APK for the phone: the JS is bundled in, so it runs without Metro, against the local edge through
# `adb reverse` (EXPO_PUBLIC_API_BASE_URL). Release builds drop expo-dev-client. Signed with the debug key, as the
# Expo template's release build is, so it installs over the dev build (same package) and keeps its data; that is fine
# for testing, not for a store. APP_ENV stays unset so the manifest keeps cleartext HTTP for http://localhost:8088.
# Needs ~6 GB free: stop `make dev` first. No daemons: Gradle and the Kotlin compiler run in this process and exit with
# it, and nothing else is stopped (`make api` and `make worker` run under Gradle too). Kotlin in-process shares Gradle's
# heap, so this build raises it above gradle.properties' 2 GB (which ran out compiling the native modules, 2025-08-30).
# Four workers, not one per core: 20 parallel native (CMake) builds took ~9 GB in a minute. Only arm64-v8a, the
# phones we test on (a Pixel), instead of all four ABIs a release build makes by default.
# Install: see the README's quick start.
APK := $(CURDIR)/app/android/app/build/outputs/apk/release/app-release.apk
# TARGET=aws: the same build for the AWS address, HTTPS only, via ops/aws/apk.sh, which calls
# this target back with APK_API_URL and APK_APP_ENV=production.
APK_API_URL ?= http://localhost:8088
apk: ## Build a release APK (debug-signed, JS bundled, local edge) for the phone; install with adb install -r. TARGET=aws: for the AWS address (app/build/soundcheck-aws.apk)
	if [ "$(TARGET)" = aws ]; then exec $(CURDIR)/ops/aws/apk.sh; fi
	if J17=$$(ls -d $$HOME/.sdkman/candidates/java/17* 2>/dev/null | head -1) && [ -n "$$J17" ]; then export JAVA_HOME="$$J17"; fi
	app_env="$(if $(APK_APP_ENV),APP_ENV=$(APK_APP_ENV),-u APP_ENV)"
	cd $(CURDIR)/app
	# Always, not only when android/ is missing: a new native module or config plugin (expo-notifications and
	# google-services.json) only reaches the native project through prebuild. In place, not --clean.
	env $$app_env npx expo prebuild --platform android --no-install
	cd android
	env $$app_env EXPO_PUBLIC_API_BASE_URL=$(APK_API_URL) \
	  ./gradlew --no-daemon --max-workers=4 -Pkotlin.compiler.execution.strategy=in-process \
	  "-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g" -PreactNativeArchitectures=arm64-v8a assembleRelease
	ls -lh $(APK)
	echo "install: adb install -r $(APK)   (replaces the dev build; 'make android' puts it back)"

web: ## Export the web build (expo export -p web) to app/dist; EXPO_PUBLIC_API_BASE_URL= (empty) makes it talk to whichever origin serves it (AWS)
	# --max-workers 4: Metro otherwise starts one transform worker per core (20 here), and under a 4 GB
	# cap the build was OOM-killed (first deploy, 2025-09-28); with 4 it takes ~15 s, 2.4 GB peak.
	cd $(CURDIR)/app && npx expo export -p web --max-workers 4

# ── Quality ──────────────────────────────────────────────────────────────────
test: ## Run all unit and integration tests (backend, app, lab, infra)
	$(GRADLE) test
	cd $(CURDIR)/app
	CI=1 npm test
	cd $(CURDIR)/lab
	uv run pytest -q
	cd $(CURDIR)/infra
	CI=1 npm test

verify: ## Format check, lint, typecheck, all tests, openapi-check, parity
	$(GRADLE) spotlessCheck build
	cd $(CURDIR)/app
	npm run format:check
	npm run lint
	npm run typecheck
	CI=1 npm test
	$(MAKE) -C $(CURDIR) openapi-check-client
	cd $(CURDIR)/infra
	npm run build
	CI=1 npm test
	$(MAKE) -C $(CURDIR) lab-check

lab-check: ## ruff, mypy, pytest for the Python lab
	cd $(CURDIR)/lab
	uv sync --quiet
	uv run ruff check .
	uv run ruff format --check .
	uv run mypy
	uv run pytest -q

e2e: ## Playwright web journeys through the edge (needs `make up`, `make api`, `make worker` and `make app` running, and `make clips` once)
	curl -sf http://localhost:8088/api/v1/health >/dev/null || { echo "API not reachable via the edge — run 'make up' and 'make api'"; exit 1; }
	curl -sf -o /dev/null http://localhost:8081/ || { echo "Expo web server not running — run 'make app'"; exit 1; }
	ls $(CURDIR)/fixtures/clips/clip-*.mp4 >/dev/null 2>&1 || { echo "No synthetic clips — run 'make clips' (the upload journey needs them)"; exit 1; }
	cd $(CURDIR)/app
	# The journeys leave @example.com accounts and their (partly silent) clips in the shared database;
	# soft-delete those posts afterwards so they never reach a feed.
	status=0
	CI=1 npx playwright test || status=$$?
	$(CURDIR)/ops/scripts/prune-test-posts.sh --apply || true
	exit $$status

screens: ## Web screenshots for docs/screens/web (synthetic clips; needs `make dev` and `make clips`; CLIPS_DIR=… from a worktree)
	curl -sf http://localhost:8088/api/v1/health >/dev/null || { echo "API not reachable via the edge — run 'make dev'"; exit 1; }
	cd $(CURDIR)/app
	status=0
	npx playwright test --config screens.config.ts $(ARGS) || status=$$?
	$(CURDIR)/ops/scripts/prune-test-posts.sh --apply || true
	exit $$status

diagrams: ## Draw the diagrams (docs/diagrams): SVG from build.py's data, then PNG with headless Chrome, one at a time
	python3 $(CURDIR)/docs/diagrams/build.py --png

diagrams-check: ## Fail if a diagram has > 12 boxes, text < 24 px, a source missing at HEAD, or a stale SVG/PNG
	python3 $(CURDIR)/docs/diagrams/build.py --check

openapi: ## Regenerate contracts/openapi.json and the TypeScript client (orval)
	$(GRADLE) test --tests app.soundcheck.OpenApiSnapshotIT -PopenapiWrite=true
	cd $(CURDIR)/app
	npx orval

openapi-check: ## Fail if regenerating the OpenAPI snapshot/client would change anything
	$(GRADLE) test --tests app.soundcheck.OpenApiSnapshotIT
	$(MAKE) -C $(CURDIR) openapi-check-client

openapi-check-client: ## Fail if running orval changes app/src/api/generated (snapshot is checked by the backend tests)
	cd $(CURDIR)/app
	before=$$(find src/api/generated -type f | sort | xargs sha256sum | sha256sum)
	npx orval
	after=$$(find src/api/generated -type f | sort | xargs sha256sum | sha256sum)
	[ "$$before" = "$$after" ] || { echo "generated client is stale — run 'make openapi' and commit"; exit 1; }

# ── Data ─────────────────────────────────────────────────────────────────────
ingest: ## Run a gig ingestion once and exit (per INGESTION_MODE in .env; offline: make ingest INGESTION_MODE=fixtures; needs `make up`)
	SOUNDCHECK_ROLES=cli SERVER_PORT=8099 $(GRADLE) bootRun --args='--spring.profiles.active=local --soundcheck.ingestion.cli=ingest --logging.level.org.jooq.tools.LoggerListener=info'
	$(COMPOSE) exec -T db psql -U soundcheck -d soundcheck -c "select source, trigger, status, to_char(started_at, 'YYYY-MM-DD HH24:MI') as started, stats->>'fetched' as fetched, stats->>'created' as created, stats->>'updated' as updated, stats->>'unchanged' as unchanged, stats->>'merged' as merged, stats->>'skipped' as skipped, stats->>'errors' as errors, stats->>'apiCalls' as api_calls from ingestion.ingestion_run order by started_at desc limit 3"

backfill-past-gigs: ## Store the past gigs (setlist.fm) of every act on a gig with clips; at most 800 calls a run, the next run resumes (needs `make up`, SETLISTFM_API_KEY)
	SOUNDCHECK_ROLES=cli SERVER_PORT=8099 INGESTION_MODE=live INGESTION_SETLISTFM_BACKFILL=true $(GRADLE) bootRun --args='--spring.profiles.active=local --soundcheck.ingestion.cli=ingest --soundcheck.ingestion.cli-sources=SETLISTFM'
	$(COMPOSE) exec -T db psql -U soundcheck -d soundcheck -c "select status, to_char(started_at, 'YYYY-MM-DD HH24:MI') as started, stats->>'fetched' as setlists, stats->>'created' as created, stats->>'merged' as merged, stats->>'unchanged' as unchanged, stats->>'flaggedForReview' as review, stats->>'errors' as errors, stats->>'apiCalls' as api_calls from ingestion.ingestion_run where source = 'SETLISTFM' order by started_at desc limit 3" -c "select count(*) as acts_read, (select count(*) from catalog.gig_source where source = 'SETLISTFM') as setlist_links from ingestion.setlistfm_artist_sync"
purge-past-gigs: ## Remove what the setlist.fm backfill stored that no one has posted to or marked (needs `make up`)
	$(COMPOSE) exec -T db psql -U soundcheck -d soundcheck -v ON_ERROR_STOP=1 -f - < $(CURDIR)/ops/scripts/purge-setlistfm-backfill.sql
purge-fixtures: ## Remove the synthetic fixture gigs, venues and artists from the local database (needs `make up`)
	$(COMPOSE) exec -T db psql -U soundcheck -d soundcheck -v ON_ERROR_STOP=1 -f - < $(CURDIR)/ops/scripts/purge-synthetic.sql
prune-local: ## Mark local posts whose clip files LocalStack lost as deleted (needs `make up`)
	$(CURDIR)/ops/scripts/prune-local.sh
prune-test-posts: ## Soft-delete the posts of @example.com test accounts (dry run; APPLY=1 to do it; needs `make up`)
	$(CURDIR)/ops/scripts/prune-test-posts.sh $(if $(APPLY),--apply)
venue-coverage: ## For each venue read from its own site: its shows in the next 60 days that Ticketmaster misses (after an ingest)
	SOUNDCHECK_ROLES=cli SERVER_PORT=8099 $(GRADLE) bootRun -q --args='--spring.profiles.active=local --soundcheck.ingestion.cli=venue-coverage'

venue-preview: ## What venue sites would give, nothing written (SITES=key1,key2 from venues/london.json; live fetch, robots.txt honoured)
	SOUNDCHECK_ROLES=cli SERVER_PORT=8099 INGESTION_MODE=live $(GRADLE) bootRun -q --args='--spring.profiles.active=local --soundcheck.ingestion.cli=venue-preview --soundcheck.ingestion.venues.enabled=true --soundcheck.ingestion.venues.sites=$(SITES) --logging.level.root=warn'

record-fixtures: ## Record external API responses into fixtures/ (live calls; needs the keys in .env and `make up`)
	SOUNDCHECK_ROLES=cli SERVER_PORT=8099 INGESTION_MODE=live $(GRADLE) bootRun --args='--spring.profiles.active=local --soundcheck.ingestion.cli=record-fixtures --logging.level.org.jooq.tools.LoggerListener=info'
	$(CURDIR)/ops/scripts/check-fixture-secrets.sh

backfill-setlists: ## The owner's gigs with their full bills from setlist.fm, into bills.json; other dates to .localdata/setlistfm/candidates.csv for review (live calls within a daily budget, resumable; options: ARTISTS="Keo,SPRINTS" FROM=2024-06 DRY_RUN=1)
	$(UV_LAB) run soundcheck-lab backfill-setlists $(if $(ARTISTS),--artists "$(ARTISTS)") $(if $(FROM),--from $(FROM)) $(if $(DRY_RUN),--dry-run)

seed-demo: ## Seed personas, the owner's real gigs and clips (if fixtures/clips/mine/ exists), likes and engagement through the API (idempotent; needs `make up`, `make api`, `make worker`; options: SEED_ARGS="--personas 3 --gigs 2"; TARGET=aws seeds the AWS site)
	@if [ "$(TARGET)" = aws ]; then exec $(CURDIR)/ops/aws/seed.sh $(SEED_ARGS); fi
	curl -sf http://localhost:8088/api/v1/health >/dev/null || { echo "API not reachable via the edge — run 'make up', 'make api' and 'make worker'"; exit 1; }
	test -n "$$(ls $(CURDIR)/fixtures/clips/demo/demo-*.mp4 2>/dev/null)" || python3 $(CURDIR)/ops/scripts/make_clips.py --demo
	n=$$($(COMPOSE) exec -T db psql -U soundcheck -d soundcheck -Atc "select count(*) from catalog.gig where starts_at > now()"); \
	  [ "$$n" -gt 0 ] || { echo "No upcoming gigs in the catalog: run 'make ingest' first"; exit 1; }
	$(UV_LAB) run soundcheck-lab seed-demo $(SEED_ARGS)

seed-acceptance: ## The from-empty seed check, safely: make backup, then reset → up → fixtures ingest → seed-demo --no-real in < 15 min, every For You >= 30 (stop make dev first; asks before the reset unless YES=1; restore with make restore)
	$(CURDIR)/ops/scripts/seed-acceptance.sh

backup: ## Save the local database, every clip and the seed's progress to .localdata/backups/<stamp> (needs make up)
	$(CURDIR)/ops/scripts/backup.sh

restore: ## Put a backup back: make restore BACKUP=.localdata/backups/<stamp> (stop make dev first; asks unless YES=1)
	$(CURDIR)/ops/scripts/restore.sh "$(BACKUP)"

seed-scale: ## Generate the performance dataset into the perf database (PROFILE=small|medium, SEED=42; needs make perf-up and, once, make perf-api)
	$(UV_LAB) run soundcheck-lab seed-scale --profile $(or $(PROFILE),small) --seed $(or $(SEED),42)

# A separate perf stack (ops/perf/compose.perf.yaml, compose project soundcheck-perf), never the dev stack.
perf-up: ## Start the perf Postgres (127.0.0.1:55500) and the -perf bucket and queue in LocalStack (if running)
	$(CURDIR)/ops/perf/perf.sh up

perf-down: ## Stop the perf stack (keeps its volume) and delete the -perf bucket and queue
	$(CURDIR)/ops/perf/perf.sh down

perf-reset: ## Drop the perf database's volume (prints it and asks unless YES=1)
	$(CURDIR)/ops/perf/perf.sh reset

perf-api: ## Run the jar with profile perf on 8201 (taskset PERF_API_CPUS, timeout 3h; REBUILD=1 rebuilds the jar)
	$(CURDIR)/ops/perf/perf.sh api

perf-api-stop: ## Stop the perf API started by make perf-api
	$(CURDIR)/ops/perf/perf.sh api-stop

clips: ## Generate 20 synthetic test clips into fixtures/clips/ with FFmpeg (deterministic)
	python3 $(CURDIR)/ops/scripts/make_clips.py

clips-check: ## Regenerate the clips into a temp dir and prove they are byte-identical to fixtures/clips/
	python3 $(CURDIR)/ops/scripts/make_clips.py --check

# ── Ranking lab ──────────────────────────────────────────────────────────────
simulate: ## Synthetic personas on the simulator's OWN stack: make simulate SIM_API=http://localhost:8140 SIM_DB=postgresql://… [SEED=7 PERSONAS=200 SESSIONS=5 DAYS=14]
	[ -n "$(SIM_API)" ] && [ -n "$(SIM_DB)" ] || { echo "simulate: set SIM_API and SIM_DB to the simulator's own API and database (never the dev stack)"; exit 1; }
	$(UV_LAB) run soundcheck-lab simulate --api "$(SIM_API)" --db "$(SIM_DB)" --seed $(or $(SEED),7) --personas $(or $(PERSONAS),200) --sessions $(or $(SESSIONS),5) --days $(or $(DAYS),14)

# The loop's targets reach a database and an API only through LAB_DATABASE_URL and LAB_API, which have no
# defaults: never the dev stack. E.g. LAB_API=http://localhost:8141
# LAB_DATABASE_URL=postgresql://soundcheck:soundcheck@localhost:55481/soundcheck
lab-extract: ## Training table from served items (LAB_DATABASE_URL=… [SINCE=14d SIM=lab/data/sim-….json])
	[ -n "$${LAB_DATABASE_URL:-}" ] || { echo "lab-extract: set LAB_DATABASE_URL to the lab's own database (never the dev stack's)"; exit 1; }
	$(UV_LAB) run soundcheck-lab extract --since $(or $(SINCE),14d) $(if $(SIM),--sim "$(abspath $(SIM))")

lab-train: ## Train ranking weights on the earlier rounds ([DATA=lab/data/extract-….parquet], default the newest)
	$(UV_LAB) run soundcheck-lab train $(if $(DATA),--data "$(abspath $(DATA))")

lab-eval: ## Held-out metrics, active config vs a trained one (LAB_DATABASE_URL=… REPORT=lab/reports/<ts>)
	[ -n "$${LAB_DATABASE_URL:-}" ] && [ -n "$(REPORT)" ] || { echo "lab-eval: set LAB_DATABASE_URL (the lab's own database) and REPORT=lab/reports/<ts>"; exit 1; }
	$(UV_LAB) run soundcheck-lab evaluate --configs "active,$(abspath $(REPORT))/model.json"

lab-publish: ## Publish a trained config as a LAB DRAFT via the API (LAB_API=… REPORT=lab/reports/<ts>)
	[ -n "$${LAB_API:-}" ] && [ -n "$(REPORT)" ] || { echo "lab-publish: set LAB_API (the lab's own API) and REPORT=lab/reports/<ts>"; exit 1; }
	$(UV_LAB) run soundcheck-lab publish "$(abspath $(REPORT))"

lab-loop: ## simulate (40 × 3 × 6) → extract → train → evaluate → publish, on the lab's OWN stack (LAB_API=… LAB_DATABASE_URL=… [SEED=11] [CLICK="alpha=2.0 w_artist=0.7"])
	[ -n "$${LAB_API:-}" ] && [ -n "$${LAB_DATABASE_URL:-}" ] || { echo "lab-loop: set LAB_API and LAB_DATABASE_URL to the lab's own stack (never the dev stack)"; exit 1; }
	$(UV_LAB) run soundcheck-lab loop $(if $(SEED),--seed $(SEED)) $(foreach c,$(CLICK),--click $(c))

# ── Performance and operations ───────────────────────────────────────────────
load: ## Run a recorded k6 scenario against the perf API (SCENARIO=feed|following|gigs|events|upload|mixed, PROFILE=medium, RATE_SCALE=1.0, DURATION=1m, PERF_API_PORT=8200)
	SCENARIO=$(or $(SCENARIO),feed) PROFILE=$(or $(PROFILE),medium) $(CURDIR)/ops/perf/run.sh

# Container images. The jar is built here on the host, not inside `docker build`: compiling needs
# Docker itself (jOOQ's code generation starts PostGIS with Testcontainers). The api and worker images only
# layer that jar (context backend/build/docker: the jar alone); the lab image builds from the repository root
# (.dockerignore lets in lab/ and contracts/ranking/ only). Tags: <12-char commit> and `local`. A tree with
# uncommitted changes gets <commit>-dirty, so such an image is never mistaken for the commit (and never pushed).
IMAGE_TAG = $(shell git -C $(CURDIR) rev-parse --short=12 HEAD)$(shell git -C $(CURDIR) diff --quiet HEAD -- 2>/dev/null && test -z "$$(git -C $(CURDIR) ls-files --others --exclude-standard)" || echo -dirty)
images: ## Build the api, worker and lab container images (linux/amd64), tagged <commit> and local
	$(GRADLE) --no-daemon "-Dorg.gradle.jvmargs=-Xmx1g" bootJar
	jar=$$(ls $(CURDIR)/backend/build/libs/*.jar | grep -v -- '-plain\.jar$$')
	mkdir -p $(CURDIR)/backend/build/docker
	cp "$$jar" $(CURDIR)/backend/build/docker/app.jar
	tag=$(IMAGE_TAG)
	for image in api worker; do
	  docker build --platform linux/amd64 -f $(CURDIR)/backend/Dockerfile.$$image \
	    -t soundcheck-$$image:$$tag -t soundcheck-$$image:local $(CURDIR)/backend/build/docker
	done
	docker build --platform linux/amd64 -f $(CURDIR)/lab/Dockerfile -t soundcheck-lab:$$tag -t soundcheck-lab:local $(CURDIR)
	docker image ls --format '{{.Repository}}:{{.Tag}}  {{.Size}}' | grep -E "^soundcheck-(api|worker|lab):$$tag "

dlq-redrive: ## Move messages from the transcode DLQ back to the main queue
	$(call not_yet,dlq-redrive)

demo-preflight: ## Check everything needed for a live demo is up and seeded
	$(call not_yet,demo-preflight)

# ── AWS (billable) ───────────────────────────────────────────────────────────
# Deployed and operated from the laptop: each target is one script in ops/aws/. Anything that
# changes AWS asks first (type the env name; YES=1 skips). DRY_RUN=1 prints what would change and changes
# nothing. Profile soundcheck, region eu-north-1 (fixed in ops/aws/lib.sh). README "Running on AWS".
aws-check: ## Read-only: sign-in, CDK bootstrap, quotas, PG versions, prefix list, stacks, .env keys (names only)
	$(CURDIR)/ops/aws/check.sh

aws-bootstrap: ## Once per account: CDK's own CDKToolkit stack (asks)
	$(CURDIR)/ops/aws/bootstrap.sh

aws-secrets: ## Create or update the app secret soundcheck/<env>/app from .env and .secrets/aws/ (only when it changed; never printed)
	$(CURDIR)/ops/aws/secrets.sh

aws-push: ## Push soundcheck-{api,worker}:<commit> to ECR (builds with make images if needed; clean tree only; PUSH_LAB=1 adds the lab)
	$(CURDIR)/ops/aws/push.sh

aws-diff: ## Read-only: cdk diff of every stack with the context aws-deploy would pass
	$(CURDIR)/ops/aws/diff.sh

aws-deploy: ## Deploy everything in order (check, web, network/data/edge, secret, images, compute/observability/web, smoke; billable, asks)
	$(CURDIR)/ops/aws/deploy.sh

aws-smoke: ## Smoke-test the deployed site through CloudFront (upload → READY, media delivery, edge rules)
	$(CURDIR)/ops/aws/smoke.sh

aws-status: ## Read-only: services, database, queue and DLQ, the public address
	$(CURDIR)/ops/aws/status.sh

aws-logs: ## Read-only: follow the logs, SERVICE=api|worker|job
	$(CURDIR)/ops/aws/logs.sh $(SERVICE)

aws-ingest: ## One gig ingestion on AWS as a one-off Fargate task (asks; a few cents)
	$(CURDIR)/ops/aws/ingest.sh

aws-backfill-past-gigs: ## The setlist.fm past-gigs backfill on AWS as a one-off task (asks; a few cents)
	$(CURDIR)/ops/aws/ingest.sh --backfill

aws-seed: ## seed-demo against the AWS site (SEED_ARGS="--no-real" for the from-empty run; asks)
	$(MAKE) seed-demo TARGET=aws

aws-pause: ## Cost brake: API and workers to 0, database stopped (about $$22 a month while paused; asks)
	$(CURDIR)/ops/aws/pause.sh

aws-resume: ## Undo aws-pause: database, services, then aws-smoke (asks). Resume before any aws-deploy
	$(CURDIR)/ops/aws/resume.sh

aws-destroy: ## Destroy every stack (asks twice; the database leaves a final snapshot; clips kept unless a third answer)
	$(CURDIR)/ops/aws/destroy.sh

aws-lab: ## Cut: the lab runs locally
	@echo "cut: the lab runs locally"

aws-test: ## Offline, no AWS calls: shellcheck the deploy scripts, the dry-run test against recorded outputs, and the task-definition contract check
	$(CURDIR)/ops/aws/test/run.sh
	$(CURDIR)/ops/aws/test/contract-check.sh
