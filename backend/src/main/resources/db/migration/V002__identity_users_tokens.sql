-- V002 — identity: users, refresh tokens, explicit genre preferences.

create table identity.app_user (
  id              uuid primary key,
  email           citext not null unique,
  handle          citext not null unique check (handle ~ '^[a-z0-9_]{3,30}$'),
  display_name    text   not null check (char_length(display_name) between 1 and 50),
  bio             text   check (char_length(bio) <= 300),
  avatar_media_id uuid,                                   -- media.media_asset (IMAGE), FK added with the media schema
  home_city_id    int,                                    -- catalog.city (FK added in V003)
  home_location   geography(Point,4326),                  -- city centroid, never device GPS
  password_hash   text   not null,                        -- Argon2id PHC string
  role            text   not null default 'USER' check (role in ('USER','ADMIN')),
  onboarded_at    timestamptz,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz
);
-- Handle search / autocomplete (profiles, @mentions later).
create index app_user_handle_trgm on identity.app_user using gin (handle gin_trgm_ops);

create table identity.refresh_token (
  id           uuid primary key,
  user_id      uuid not null references identity.app_user(id) on delete cascade,
  family_id    uuid not null,                   -- all rotations of one login share a family
  token_hash   bytea not null unique,           -- sha256(raw token); the raw token is never stored
  expires_at   timestamptz not null,
  revoked_at   timestamptz,
  replaced_by  uuid,
  client       text not null check (client in ('android','web','lab','test')),
  user_agent   text,
  created_at   timestamptz not null default now()
);
-- Family-wide revocation on reuse detection: lookup by family_id.
create index refresh_token_family on identity.refresh_token (family_id);

create table identity.user_genre_preference (   -- explicit, from onboarding/settings
  user_id    uuid not null references identity.app_user(id) on delete cascade,
  genre_id   smallint not null,                 -- catalog.genre (FK added in V003)
  weight     real not null default 1.0 check (weight between 0 and 1),
  created_at timestamptz not null default now(),
  primary key (user_id, genre_id)
);
