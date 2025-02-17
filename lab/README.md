# lab

Python 3.13 ranking lab (uv project, package `soundcheck_lab`, CLI `soundcheck-lab`).

```bash
uv sync                      # creates .venv with runtime + dev deps
uv run soundcheck-lab --help
uv run soundcheck-lab schema # prints contracts/ranking/feature-schema.json
make lab-check               # from the repo root: ruff, mypy --strict, pytest
```

Commands other than `version`/`schema` are stubs for now (`seed-scale`, `simulate`, `extract`, `train`,
`evaluate`, `publish`); `parity` comes later, with the scorer. `data/` and `reports/` are gitignored.
