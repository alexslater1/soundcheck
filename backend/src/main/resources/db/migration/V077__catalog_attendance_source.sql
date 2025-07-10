-- Why a fan is marked at a gig. USER: they said so (the attendance buttons, Add a gig).
-- POST: a post of theirs from the gig went live, and that is the only reason they are marked WENT. A
-- POST mark is removed when their last live post of the gig goes; a USER mark is never touched by posts.
-- A constant default makes this a metadata-only change: existing rows read as USER, as they were.
alter table catalog.gig_attendance
  add column source text not null default 'USER' check (source in ('USER', 'POST'));
