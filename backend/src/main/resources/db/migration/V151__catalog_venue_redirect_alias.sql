-- Merging a duplicate venue into the real one. Like a gig merge (V148), the
-- duplicate's row is deleted once everything pointing at it has moved, and two tables keep it findable.

-- Its id: GET /venues/{id} answers a redirected id with the survivor, so an old link, a follow-list
-- snapshot or a notification's subject (notify.notification.subject_id, no foreign key) still opens it.
-- A redirect goes with the venue it points at; a later merge of that venue re-points it (chains A→B→C).
create table catalog.venue_redirect (
  from_venue_id uuid primary key,
  into_venue_id uuid not null references catalog.venue(id) on delete cascade,
  merged_at     timestamptz not null default now(),
  merged_by     uuid references identity.app_user(id) on delete set null
);
create index venue_redirect_into on catalog.venue_redirect (into_venue_id);   -- re-pointing a chain

-- Its name: a fan adding a gig, or a setlist.fm setlist,
-- that names the old venue near the same place resolves to the survivor instead of a fresh copy.
-- One venue per normalised name: the latest merge decides.
create table catalog.venue_alias (
  name_norm  text primary key,                    -- NameNormaliser's form, as catalog.venue.name_norm
  venue_id   uuid not null references catalog.venue(id) on delete cascade,
  created_at timestamptz not null default now()
);
create index venue_alias_venue on catalog.venue_alias (venue_id);              -- re-pointing a chain
