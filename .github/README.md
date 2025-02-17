# .github

- `workflows/ci.yml` — path-filtered jobs `backend` (Gradle build + Testcontainers tests), `app` (lint, typecheck, jest, orval drift, web export), `lab` (ruff, mypy, pytest), `infra` (tsc, jest, offline synth) plus `gitleaks`. All jobs run on `main`; PRs run only what changed (skipped jobs count as green).
- `workflows/codeql.yml` — CodeQL for Java, TypeScript and Python; skips itself while the repository is private.
- `workflows/deploy.yml` — planned (OIDC deploy).
- `dependabot.yml` — weekly grouped updates for gradle, npm (app, infra) and GitHub Actions.

Secrets used: none (the jobs need only the built-in `GITHUB_TOKEN`).
