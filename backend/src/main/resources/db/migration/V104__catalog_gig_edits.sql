-- Fans fix a gig's details. An act a fan added to the bill remembers who added it, so a
-- change of the gig's listing (which rewrites the source's part of the bill) keeps it.
alter table catalog.gig_artist add column added_by uuid references identity.app_user(id) on delete set null;

-- Every edit, with its author and the value before and after, so a bad one can be found and undone.
create table catalog.gig_edit (
  id         uuid primary key,
  gig_id     uuid not null references catalog.gig(id) on delete cascade,
  user_id    uuid references identity.app_user(id) on delete set null,
  field      text not null check (field in ('ACT_ADDED', 'STARTS_AT', 'DATE', 'VENUE')),
  old_value  text,
  new_value  text not null,
  created_at timestamptz not null default now()
);
create index gig_edit_gig on catalog.gig_edit (gig_id, created_at);   -- a gig's history, newest last
create index gig_edit_user on catalog.gig_edit (user_id, created_at); -- one fan's edits (to undo a run of bad ones)
