# lab

Python 3.13 ranking lab (uv project, package `soundcheck_lab`, CLI `soundcheck-lab`).

```bash
uv sync                      # creates .venv with runtime + dev deps
uv run soundcheck-lab --help
uv run soundcheck-lab schema # prints contracts/ranking/feature-schema.json
make lab-check               # from the repo root: ruff, mypy --strict, pytest
```

`seed-demo` is below; the other commands are `seed-scale`, `simulate`, `extract`, `train`, `evaluate`,
`publish` and `parity`. `data/` and `reports/` are gitignored.

## Demo seeding (`make seed-demo`)

Seeds the local stack **through the API only** (`src/soundcheck_lab/seed/`): 30 personas (abstract generated
avatars, home cities, genres, follows), a "demo fan" account, likes, comments and a labelled-synthetic
engagement pass. Needs `make up`, `make api` and `make worker`.

If `fixtures/clips/mine/` exists (my own concert clips and gig list; local only, gitignored, never
committed or deployed), it also adds his real gig history and posts his clips:

| File in `fixtures/clips/mine/` | Role |
|---|---|
| `bills.json` | His gigs with their full bills (prepared once from his gig list and setlist.fm) |
| `skip.csv` | Nights with clips that were not gigs |
| `PXL_*.mp4`, `transferred/` | The clips (phone: time + GPS inside; old phone: dates in `transferred/index.csv`) |
| `manifest.csv` | Written by the run: which clip went to which gig, acts and persona, or why it was skipped |

Each clip goes to the gig of its night (a clip at 01:30 belongs to the evening before), phone clips only
if filmed within 60 km of the gig's city. A few clips per gig are posted, spread over the night, trimmed
to 45 s and re-encoded at the pipeline's top rendition (FFmpeg memory-capped, nice 19). Acts are tagged
only when certain (a one-act bill, or as many sets of clips as acts on the bill), else the whole bill.

```bash
make seed-demo                                  # everything
make seed-demo SEED_ARGS="--personas 3 --gigs 2"   # a small run
uv run soundcheck-lab seed-demo --no-real --no-engage
```

Re-running changes nothing: accounts log in when they exist, gigs resolve to the same gig (setlist.fm
link or same night at the venue), and `.localdata/seed/state.json` remembers each posted clip (checked
against the API, so a reset database is seeded again).
