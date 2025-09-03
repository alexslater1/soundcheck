-- V136 — ingestion: when each act's past gigs were last read from setlist.fm.
-- Our own bookkeeping, no setlist.fm data: lets a backfill stopped by the daily call cap resume the next
-- day with the acts it has not read yet, and lets later runs read only what is new since the last read.
-- No foreign key: catalog.artist belongs to another module, and a row left by a merged artist is harmless.

create table ingestion.setlistfm_artist_sync (
  artist_id    uuid primary key,
  synced_at    timestamptz not null,              -- the read finished: every page back to the cutoff
  setlists     integer not null check (setlists >= 0) -- how many of the act's setlists that read kept
);
