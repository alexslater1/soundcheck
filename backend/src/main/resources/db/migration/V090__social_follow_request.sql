-- V090 — social: follow requests. Following a private profile asks its owner first.
-- A request is not a follow: social.user_follow keeps only accepted follows, so counts, lists and the
-- Following feed need no change. Accepting moves the row into user_follow.

create table social.follow_request (
  requester_id uuid not null references identity.app_user(id) on delete cascade,
  owner_id     uuid not null references identity.app_user(id) on delete cascade,
  created_at   timestamptz not null default now(),
  primary key (requester_id, owner_id),              -- "have I asked to follow them?" (the profile's Requested)
  check (requester_id <> owner_id)
);
create index follow_request_owner on social.follow_request (owner_id, created_at desc);  -- "who has asked to follow me?"
