-- V010 — social: user follows and blocks. Artist follows are V006.

create table social.user_follow (
  follower_id uuid not null references identity.app_user(id) on delete cascade,
  followee_id uuid not null references identity.app_user(id) on delete cascade,
  created_at  timestamptz not null default now(),
  primary key (follower_id, followee_id),            -- "who do I follow" (following list, Following feed)
  check (follower_id <> followee_id)
);
create index user_follow_followee on social.user_follow (followee_id, created_at desc);   -- "who follows me", follower counts

create table social.user_block (
  blocker_id uuid not null references identity.app_user(id) on delete cascade,
  blocked_id uuid not null references identity.app_user(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id),              -- "whom have I blocked"
  check (blocker_id <> blocked_id)
);
create index user_block_blocked on social.user_block (blocked_id);                        -- "who has blocked me" (both directions are checked)
