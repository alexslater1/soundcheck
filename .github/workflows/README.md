# CI

- `ci.yml` — path-filtered jobs `backend` (Gradle build + Testcontainers tests), `app` (lint, typecheck, jest, orval drift, web export), `lab` (ruff, mypy, pytest), `infra` (tsc, jest, offline synth), `images` (the api, worker and lab images, built and started read-only, not pushed) and `e2e` (the Playwright journeys in Google Chrome against the whole stack on the runner), plus `gitleaks`. All jobs run on `main`; PRs run only what changed (skipped jobs count as green).
- `codeql.yml` — CodeQL for Java, TypeScript and Python; skips itself while the repository is private.
- `../dependabot.yml` — weekly grouped updates for gradle, npm (app, infra), uv, GitHub Actions and compose images.

Secrets used: none (the jobs need only the built-in `GITHUB_TOKEN`). There is no deploy job: AWS is deployed from the laptop (`make aws-deploy`, see the root README).
