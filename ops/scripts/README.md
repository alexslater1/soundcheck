# ops/scripts
Operational scripts, mostly run through `make` (the target in brackets).
- `dev.sh` — the whole local stack from one terminal (`make dev`).
- `local-status.sh` — what is running: containers, api/worker/expo, build daemons, phone forwards (`make status`; `--leftovers` at the end of `make down`).
- `gen_fixtures.py` — the synthetic Ticketmaster, Last.fm and MusicBrainz fixtures, deterministic.
- `check-fixture-secrets.sh` — fails if a secret value from `.env` appears in `fixtures/` (or the given paths); prints only names.
- `purge-synthetic.sql` — removes the synthetic fixture gigs, venues and artists from the local database (`make purge-fixtures`).
- `make_clips.py` — synthetic test clips with FFmpeg (`make clips`; `--demo` writes the 150-clip demo set to `fixtures/clips/demo/`).
- `check-media-delivery.sh` — checks a READY clip is served through the local edge the way CloudFront will serve it.
- `prune-local.sh` — marks as deleted the posts whose clip files LocalStack lost (`make prune-local`).
- `make_icons.py` — draws the app icon, Android adaptive layers, favicon and splash image from the five-bar mark (plain Python).
- `backup.sh` — the local database, every clip and the seed's state to `.localdata/backups/<stamp>/` (`make backup`).
- `restore.sh` — puts such a backup back: database recreated, clips reloaded through the snapshot volume (`make restore BACKUP=…`).
- `seed-acceptance.sh` — the demo seed's acceptance check from an empty stack, after a backup (`make seed-acceptance`).
