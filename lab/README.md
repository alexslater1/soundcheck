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
committed or deployed), it also adds my real gig history and posts those clips:

| File in `fixtures/clips/mine/` | Role |
|---|---|
| `bills.json` | My gigs with their full bills (prepared once from my gig list and setlist.fm) |
| `skip.csv` | Nights with clips that were not gigs |
| `PXL_*.mp4`, `transferred/` | The clips (phone: time + GPS inside; old phone: dates in `transferred/index.csv`) |
| `manifest.csv` | Written by the run: which clip went to which gig, acts and persona, or why it was skipped |

Each clip goes to the gig of its night (a clip at 01:30 belongs to the evening before), phone clips only
if filmed within 60 km of the gig's city. Up to four clips per gig (`--clips-per-gig`, the default: enough to exercise the UI) are posted, spread
over the night, trimmed to 45 s and re-encoded to at most 1280 px at ~5 Mbit/s, the pipeline's top
rendition (FFmpeg memory-capped, nice 19); the encoded copies are kept in `.localdata/seed/encoded/`, so a
later run only uploads. Act tags (`act_tags`, the rule per clip in the manifest's `tag_rule` column): one
act on a one-act bill; one act per set only when the sets of clips fit the bill (as many sets as acts,
changeovers of 25 min or more, the first set before 21:00, the last from 20:30; still a guess, there are
no set times); otherwise the whole bill, since support acts matter; festival clips get no act. Posts are
spread over each gig's attendee personas.

The demo fan's own steps: its profile is public, so its gig stats show (profiles are public by default;
the seed only undoes a private switch with `PATCH /me {"profileVisibility": "PUBLIC"}`, checked on every
run), and once my gigs are in it follows the 15 acts it has seen most, read from its own
stats (without gigs it follows popular artists of its genres, like the personas).

The acceptance check ("`make reset && make up && make seed-demo` in under 15 minutes") holds only with
`--no-real`: with the real clips the first run takes hours (every clip is encoded, then transcoded by the
worker). `make seed-acceptance` runs it safely: it refuses while `make dev` runs, makes a
`make backup` first (database, clips, seed state in `.localdata/backups/<stamp>/`), asks, then times
reset → up → Ticketmaster fixtures → API and worker → `seed-demo --no-real --require-feed 30`, and prints
the backup's folder. `make restore BACKUP=<folder>` puts everything back. The committed default persona
password works only against a local API; seeding anywhere else needs `SEED_PASSWORD`.

```bash
make seed-demo                                  # everything
make seed-demo SEED_ARGS="--personas 3 --gigs 2"   # a small run
uv run soundcheck-lab seed-demo --no-real --no-engage
make backup                                     # database, clips, seed state → .localdata/backups/<stamp>/
make seed-acceptance                            # the acceptance check from an empty stack, backed up first
make restore BACKUP=.localdata/backups/<stamp>  # put a backup back (stop make dev first)
```

Re-running changes nothing: accounts log in when they exist, gigs resolve to the same gig (setlist.fm
link or same night at the venue), and `.localdata/seed/state.json` remembers each posted clip (checked
against the API, so a reset database is seeded again). A clip whose post FAILED is deleted and posted again.

`--require-feed N` makes the run exit 1 unless every persona's For You has at least N items (the
acceptance uses 30).
