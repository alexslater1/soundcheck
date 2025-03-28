-- V012 — notify: the notification inbox.

create table notify.notification (
  id         uuid primary key,
  user_id    uuid not null references identity.app_user(id) on delete cascade,
  type       text not null check (type in ('NEW_FOLLOWER','POST_LIKED','POST_COMMENTED','POST_READY','POST_FAILED')),
  actor_id   uuid, post_id uuid, comment_id uuid,     -- no foreign keys: the inbox outlives what it points at
  created_at timestamptz not null default now(),
  read_at    timestamptz
);
create index notification_user on notify.notification (user_id, created_at desc, id);   -- the inbox, newest first (keyset)
-- Likes are collapsed: at most one unread POST_LIKED per (user, post); how many people liked is computed
-- at read time. The index makes "insert unless one is already unread" a single statement.
create unique index notification_unread_like on notify.notification (user_id, post_id)
  where type = 'POST_LIKED' and read_at is null;
create index notification_unread on notify.notification (user_id) where read_at is null;  -- unreadCount
