-- Which acts a fan saw at a gig they went to. A Went counts the whole bill unless the fan
-- unticked an act; only those exceptions are stored, so every existing mark (and every new one, and a
-- Going that reads as Went) already counts the whole bill without a backfill, and an act added
-- to the bill later (a fan's edit) counts for everyone who went until they untick it.
-- The row goes with the mark (the cascade). No foreign key to gig_artist: ingestion rewrites a bill when
-- its listing changes, which would wipe these; a row for an act no longer on the bill matches nothing.
create table catalog.gig_attendance_missed (
  user_id    uuid not null,
  gig_id     uuid not null,
  artist_id  uuid not null references catalog.artist(id),
  created_at timestamptz not null default now(),
  primary key (user_id, gig_id, artist_id),
  foreign key (user_id, gig_id) references catalog.gig_attendance (user_id, gig_id) on delete cascade
);
