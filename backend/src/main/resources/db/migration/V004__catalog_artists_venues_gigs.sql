-- V004 — catalog: artists, venues, gigs, lineups, provenance, attendance.
-- Written by the ingestion module through catalog's public write API; read by the catalog HTTP API.

create table catalog.artist (
  id          uuid primary key,
  name        text not null,
  name_norm   text not null,                         -- NameNormaliser: lower, unaccent, punctuation stripped, leading 'the ' removed
  mbid        uuid unique,                           -- MusicBrainz ID when known
  image_url   text,
  popularity  real not null default 0,               -- 0..1, from follows/posts, recomputed nightly
  enriched_at timestamptz,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
-- Fuzzy and exact name matching (entity resolution, GET /artists?q=); pg_trgm GIN also serves equality.
create index artist_name_norm_trgm on catalog.artist using gin (name_norm gin_trgm_ops);

create table catalog.artist_genre (
  artist_id uuid not null references catalog.artist(id) on delete cascade,
  genre_id  smallint not null references catalog.genre(id),
  weight    real not null check (weight between 0 and 1),
  primary key (artist_id, genre_id)
);

create table catalog.artist_external_id (
  source      text not null check (source in ('TICKETMASTER','SKIDDLE','MUSICBRAINZ','LASTFM')),
  external_id text not null,
  artist_id   uuid not null references catalog.artist(id) on delete cascade,
  primary key (source, external_id)
);

create table catalog.venue (
  id           uuid primary key,
  name         text not null,
  name_norm    text not null,
  address      text,
  city         text,
  postcode     text,
  country_code char(2) not null default 'GB',
  location     geography(Point,4326),
  timezone     text not null default 'Europe/London',
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create index venue_location_gix on catalog.venue using gist (location);
create index venue_name_norm_trgm on catalog.venue using gin (name_norm gin_trgm_ops);

create table catalog.venue_external_id (
  source      text not null,
  external_id text not null,
  venue_id    uuid not null references catalog.venue(id) on delete cascade,
  primary key (source, external_id)
);

create table catalog.gig (
  id           uuid primary key,
  title        text not null,
  venue_id     uuid not null references catalog.venue(id),
  location     geography(Point,4326),                -- denormalised from venue for geo+time queries
  starts_at    timestamptz not null,
  doors_at     timestamptz,
  status       text not null default 'SCHEDULED' check (status in ('SCHEDULED','POSTPONED','RESCHEDULED','CANCELLED')),
  ticket_url   text,
  price_min    numeric(10,2),
  price_max    numeric(10,2),
  currency     char(3),
  image_url    text,
  content_hash bytea not null,                       -- hash of normalised fields; skip no-op updates
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create index gig_starts_at on catalog.gig (starts_at, id);            -- GET /gigs keyset order
create index gig_venue on catalog.gig (venue_id, starts_at);          -- venue pages, gig matching (same venue ± 3 h)
create index gig_location_gix on catalog.gig using gist (location);   -- radius filter (ST_DWithin)

create table catalog.gig_artist (
  gig_id        uuid not null references catalog.gig(id) on delete cascade,
  artist_id     uuid not null references catalog.artist(id),
  billing_order smallint not null,                   -- 0 = headliner
  primary key (gig_id, artist_id)
);
create index gig_artist_artist on catalog.gig_artist (artist_id, gig_id);

create table catalog.gig_source (                     -- provenance; one gig can come from several sources
  source       text not null,
  external_id  text not null,
  gig_id       uuid not null references catalog.gig(id) on delete cascade,
  url          text,
  last_seen_at timestamptz not null,
  primary key (source, external_id)
);
create index gig_source_gig on catalog.gig_source (gig_id);           -- sources[] on GET /gigs/{id}

create table catalog.gig_attendance (
  user_id    uuid not null references identity.app_user(id) on delete cascade,
  gig_id     uuid not null references catalog.gig(id) on delete cascade,
  status     text not null check (status in ('INTERESTED','GOING','WENT')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (user_id, gig_id)
);
create index gig_attendance_gig on catalog.gig_attendance (gig_id, status);
