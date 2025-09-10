-- The operator's review of fan-added gigs, and what a merged gig leaves behind.

-- A fan-added gig (created_by set, V020) the operator has looked at leaves the console's queue.
alter table catalog.gig add column reviewed_at timestamptz;
alter table catalog.gig add column reviewed_by uuid references identity.app_user(id) on delete set null;

-- The queue: fan-added gigs, newest first, keyset on (created_at, id). Partial: almost every gig comes
-- from a source and has no created_by.
create index gig_fan_added on catalog.gig (created_at desc, id desc) where created_by is not null;

-- A merge deletes the duplicate once every row pointing at it has moved. Its id keeps
-- working through this table: GET /gigs/{id} resolves a redirected id to the gig it was merged into,
-- so an old link or an old notification snapshot (notify.notification.gigs) still opens the right gig.
-- A redirect goes with the gig it points at; a later merge of that gig re-points it (chains A→B→C).
create table catalog.gig_redirect (
  from_gig_id uuid primary key,
  into_gig_id uuid not null references catalog.gig(id) on delete cascade,
  merged_at   timestamptz not null default now(),
  merged_by   uuid references identity.app_user(id) on delete set null
);
create index gig_redirect_into on catalog.gig_redirect (into_gig_id);   -- re-pointing a chain; the cascade
