# Soundcheck — every routine command. `make help` lists targets.
# Targets that aren't built yet print "not yet implemented" and exit 1.

SHELL := /bin/bash
# Each recipe runs as one script that stops at the first failing line.
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help
.ONESHELL:

COMPOSE   := docker compose
GRADLE    := cd $(CURDIR)/backend && ./gradlew
NPM_APP   := cd $(CURDIR)/app && npm
NPM_INFRA := cd $(CURDIR)/infra && npm
UV_LAB    := cd $(CURDIR)/lab && uv

# Load .env if present so targets see the same variables as the services.
ifneq (,$(wildcard .env))
include .env
export
endif

define not_yet
	@echo "$(1): not yet implemented"; exit 1
endef

.PHONY: help setup install keys up openapi-check-client down reset api worker app android web test verify lab-check e2e \
	openapi openapi-check ingest record-fixtures seed-demo seed-scale simulate lab-train lab-eval \
	lab-publish lab-loop load clips images dlq-redrive demo-preflight \
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

down: ## Stop the local stack (keeps volumes)
	$(COMPOSE) --profile full down --remove-orphans

reset: ## Stop the local stack and drop volumes
	$(COMPOSE) --profile full down --remove-orphans --volumes

# ── Run services ─────────────────────────────────────────────────────────────
api: ## Run the API (roles api,scheduler) from Gradle against the local stack
	SOUNDCHECK_ROLES=api,scheduler $(GRADLE) bootRun --args='--spring.profiles.active=local'

worker: ## Run the transcode worker (role worker) from Gradle; needs local FFmpeg
	SOUNDCHECK_ROLES=worker SERVER_PORT=8090 $(GRADLE) bootRun --args='--spring.profiles.active=local'

app: ## Start the Expo dev server (press w for web)
	cd $(CURDIR)/app && npx expo start

android: ## Build and install the Android dev build on the connected phone, then adb reverse 8088
	# The Android Gradle build needs JDK 17 (newer JDKs break AGP's CMake step); use SDKMAN's 17 when present.
	if J17=$$(ls -d $$HOME/.sdkman/candidates/java/17* 2>/dev/null | head -1) && [ -n "$$J17" ]; then export JAVA_HOME="$$J17"; fi
	# Before expo: `expo run:android` stays in the foreground as the bundler, so nothing after it runs.
	adb reverse tcp:8088 tcp:8088 && adb reverse tcp:8081 tcp:8081 && adb reverse tcp:4566 tcp:4566
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

e2e: ## Playwright web journeys through the edge (needs `make up`, `make api` and `make app` running)
	curl -sf http://localhost:8088/api/v1/health >/dev/null || { echo "API not reachable via the edge — run 'make up' and 'make api'"; exit 1; }
	curl -sf -o /dev/null http://localhost:8081/ || { echo "Expo web server not running — run 'make app'"; exit 1; }
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
ingest: ## Run a gig ingestion (fixtures or live per INGESTION_MODE)
	$(call not_yet,ingest)

record-fixtures: ## Record external API responses into fixtures/
	$(call not_yet,record-fixtures)

seed-demo: ## Seed demo personas, clips and engagement (idempotent); TARGET=aws for the cloud
	$(call not_yet,seed-demo)

seed-scale: ## Generate the performance dataset (PROFILE=small|medium|large) into DB_NAME=soundcheck_perf
	$(call not_yet,seed-scale)

clips: ## Generate synthetic test clips into fixtures/clips/ with FFmpeg
	$(call not_yet,clips)

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
