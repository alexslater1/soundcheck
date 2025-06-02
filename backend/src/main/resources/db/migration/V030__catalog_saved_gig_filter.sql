-- A fan's saved Gigs-tab searches, e.g. "Keo, Bleech 9:3 and The North in London".
-- (V030, not V021: another branch takes V021–V029; the gap keeps the two branches mergeable.)
-- Ids in arrays, not join tables: a filter is read and written whole, never queried by artist or venue,
-- and an artist or venue that disappears is skipped when the filter is shown (no foreign key to break).
create table catalog.saved_gig_filter (
  id          uuid primary key,
  user_id     uuid not null references identity.app_user(id) on delete cascade,  -- account purge removes them
  name        text not null check (char_length(name) between 1 and 60),
  artist_ids  uuid[] not null default '{}',
  venue_ids   uuid[] not null default '{}',
  genre       text,                        -- a genre slug, as GET /gigs?genre= takes it
  q           text check (char_length(q) <= 80),
  place       text not null check (place in ('HOME', 'UK', 'CITY')),
  city_id     integer references catalog.city(id),
  created_at  timestamptz not null default now(),
  check ((place = 'CITY') = (city_id is not null))
);
-- GET /me/gig-filters lists one user's filters in the order they were saved.
create index saved_gig_filter_user on catalog.saved_gig_filter (user_id, created_at);
