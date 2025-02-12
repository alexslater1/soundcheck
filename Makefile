# Soundcheck — every routine command. `make help` lists targets.
# Targets that aren't built yet print "not yet implemented" and exit 1.

SHELL := /bin/bash
.DEFAULT_GOAL := help
.ONESHELL:

COMPOSE   := docker compose
GRADLE    := cd backend && ./gradlew
NPM_APP   := cd app && npm
NPM_INFRA := cd infra && npm
UV_LAB    := cd lab && uv

# Load .env if present so targets see the same variables as the services.
ifneq (,$(wildcard .env))
include .env
export
endif

define not_yet
	@echo "$(1): not yet implemented"; exit 1
endef

.PHONY: help setup keys up down reset api worker app android web test verify lab-check e2e \
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
	[ $$ok = 1 ] && echo "toolchain OK" || { echo "toolchain incomplete (see docs/SETUP.md)"; exit 1; }

keys: ## Generate the ES256 JWT signing key in .secrets/
	$(call not_yet,keys)

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
	$(call not_yet,app)

android: ## Build and install the Android dev build on the connected phone, then adb reverse 8088
	$(call not_yet,android)

web: ## Export the web build (expo export -p web)
	$(call not_yet,web)

# ── Quality ──────────────────────────────────────────────────────────────────
test: ## Run all unit and integration tests (backend, app, lab, infra)
	$(GRADLE) test

verify: ## Format check, lint, typecheck, all tests, openapi-check, parity
	$(GRADLE) spotlessCheck build

lab-check: ## ruff, mypy, pytest for the Python lab
	$(call not_yet,lab-check)

e2e: ## Playwright web journeys against the exported web build + local stack
	$(call not_yet,e2e)

openapi: ## Regenerate contracts/openapi.json and the TypeScript client (orval)
	$(call not_yet,openapi)

openapi-check: ## Fail if regenerating the OpenAPI snapshot/client would change anything
	$(call not_yet,openapi-check)

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
