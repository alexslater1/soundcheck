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
- `prune-test-posts.sh` — soft-deletes the posts of `@example.com` test accounts, which the e2e journeys leave in the shared database (dry run; `--apply`; `make prune-test-posts`, and run by `make e2e` afterwards).
- `make_icons.py` — draws the app icon, Android adaptive layers, favicon, splash images and the in-app mark from the mic-check mark (plain Python, signed-distance shapes); `--candidates DIR` draws every candidate mark (`docs/screens/logo/`).
- `backup.sh` — the local database, every clip and the seed's state to `.localdata/backups/<stamp>/` (`make backup`).
- `restore.sh` — puts such a backup back: database recreated, clips reloaded through the snapshot volume (`make restore BACKUP=…`).
- `seed-acceptance.sh` — the demo seed's acceptance check from an empty stack, after a backup (`make seed-acceptance`).
- `check-moved-gigs.sql` — read-only: every copy of the six seeded gigs that were moved to their correct venues, with venue, listings, marks and posts.
- `check-venue-locations.sql` — read-only: venues still without coordinates (their gigs show no distance), Tramshed, and what the last runs geocoded.
