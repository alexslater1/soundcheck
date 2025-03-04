-- V006 — social: artist follows (user follows and blocks come in V010).

create table social.artist_follow (
  user_id    uuid not null references identity.app_user(id) on delete cascade,
  artist_id  uuid not null references catalog.artist(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, artist_id)
);
create index artist_follow_artist on social.artist_follow (artist_id);
