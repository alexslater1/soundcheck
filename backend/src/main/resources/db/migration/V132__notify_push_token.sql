-- V132 — notify: the devices a user's notifications are pushed to.
-- One row per Firebase Cloud Messaging registration token. The app registers its token after sign-in and
-- removes it on sign-out; a token FCM reports as gone is deleted by the sender. A phone that signs in as
-- someone else moves its token to them (the primary key is the token, not the pair).

create table notify.push_token (
  token        text primary key,                 -- FCM registration token: opaque, never logged
  user_id      uuid not null references identity.app_user(id) on delete cascade,
  platform     text not null check (platform in ('android')),
  created_at   timestamptz not null default now(),
  last_seen_at timestamptz not null default now()
);
create index push_token_user on notify.push_token (user_id);   -- the sender: one user's devices
