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

.PHONY: help setup install keys up openapi-check-client down reset stop-daemons status api worker app phone android web test verify lab-check e2e \
	openapi openapi-check ingest record-fixtures purge-fixtures seed-demo seed-scale simulate lab-train lab-eval \
	lab-publish lab-loop load clips clips-check images dlq-redrive demo-preflight guide \
	aws-deploy aws-pause aws-resume aws-destroy aws-smoke aws-lab

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

down: ## Stop the local stack (keeps volumes) and the build daemons, then list anything still running
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
	cd $(CURDIR)/app && npx expo run:android --device

web: ## Export the web build (expo export -p web)
	cd $(CURDIR)/app && npx expo export -p web

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
	CI=1 npx playwright test

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

purge-fixtures: ## Remove the synthetic fixture gigs, venues and artists from the local database (needs `make up`)
	$(COMPOSE) exec -T db psql -U soundcheck -d soundcheck -v ON_ERROR_STOP=1 -f - < $(CURDIR)/ops/scripts/purge-synthetic.sql
record-fixtures: ## Record external API responses into fixtures/ (live calls; needs the keys in .env and `make up`)
	SOUNDCHECK_ROLES=cli SERVER_PORT=8099 INGESTION_MODE=live $(GRADLE) bootRun --args='--spring.profiles.active=local --soundcheck.ingestion.cli=record-fixtures --logging.level.org.jooq.tools.LoggerListener=info'
	$(CURDIR)/ops/scripts/check-fixture-secrets.sh

seed-demo: ## Seed demo personas, clips and engagement (idempotent); TARGET=aws for the cloud
	$(call not_yet,seed-demo)

seed-scale: ## Generate the performance dataset (PROFILE=small|medium|large) into DB_NAME=soundcheck_perf
	$(call not_yet,seed-scale)

clips: ## Generate 20 synthetic test clips into fixtures/clips/ with FFmpeg (deterministic)
	python3 $(CURDIR)/ops/scripts/make_clips.py

clips-check: ## Regenerate the clips into a temp dir and prove they are byte-identical to fixtures/clips/
	python3 $(CURDIR)/ops/scripts/make_clips.py --check

# ── Ranking lab ──────────────────────────────────────────────────────────────
simulate: ## Run the engagement simulator against the local API
	$(call not_yet,simulate)

lab-train: ## Train ranking weights from served-item logs
	$(call not_yet,lab-train)

lab-eval: ## Evaluate a trained config against the active one
	$(call not_yet,lab-eval)

lab-publish: ## Publish a trained config as a DRAFT via the API
	$(call not_yet,lab-publish)

lab-loop: ## extract → train → evaluate → publish in one go
	$(call not_yet,lab-loop)

# ── Performance and operations ───────────────────────────────────────────────
load: ## Run a k6 scenario (SCENARIO=feed|gigs|events|upload|mixed, PROFILE=medium)
	$(call not_yet,load)

images: ## Build the api, worker and lab container images
	$(call not_yet,images)

dlq-redrive: ## Move messages from the transcode DLQ back to the main queue
	$(call not_yet,dlq-redrive)

demo-preflight: ## Check everything needed for a live demo is up and seeded
	$(call not_yet,demo-preflight)

# ── AWS (billable) ───────────────────────────────────────────────────────────
aws-deploy: ## cdk deploy all stacks (billable)
	$(call not_yet,aws-deploy)

aws-pause: ## Scale services to 0 and stop RDS
	$(call not_yet,aws-pause)

aws-resume: ## Resume services and RDS
	$(call not_yet,aws-resume)

aws-destroy: ## Destroy all stacks except a final RDS snapshot (destructive)
	$(call not_yet,aws-destroy)

aws-smoke: ## Smoke-test the deployed environment
	$(call not_yet,aws-smoke)

aws-lab: ## Run the lab task on ECS
	$(call not_yet,aws-lab)
