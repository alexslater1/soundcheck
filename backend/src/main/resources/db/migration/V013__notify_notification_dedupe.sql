-- V013 — notify: one inbox row per event, however often the event is delivered.
-- Listeners run through the outbox, which delivers at least once (a restart resubmits unfinished
-- publications). V012 made POST_LIKED safe; these indexes do the same for the other types, so the
-- listener's "insert … on conflict do nothing" is idempotent.

-- Rows that would break the new indexes (possible on a database that ran V012 only): keep the oldest.
delete from notify.notification n using notify.notification o
 where n.type = 'POST_COMMENTED' and o.type = 'POST_COMMENTED'
   and n.user_id = o.user_id and n.comment_id = o.comment_id and n.id > o.id;
delete from notify.notification n using notify.notification o
 where n.type in ('POST_READY','POST_FAILED') and o.type = n.type
   and n.user_id = o.user_id and n.post_id = o.post_id and n.id > o.id;
delete from notify.notification n using notify.notification o
 where n.type = 'NEW_FOLLOWER' and o.type = 'NEW_FOLLOWER' and n.read_at is null and o.read_at is null
   and n.user_id = o.user_id and n.actor_id = o.actor_id and n.id > o.id;

-- A comment is announced once.
create unique index notification_comment on notify.notification (user_id, comment_id)
  where type = 'POST_COMMENTED';
-- A post becomes ready, or fails, once.
create unique index notification_post_outcome on notify.notification (user_id, post_id, type)
  where type in ('POST_READY','POST_FAILED');
-- Follows collapse like likes: one unread NEW_FOLLOWER per (user, follower), so following, unfollowing
-- and following again does not fill the inbox.
create unique index notification_unread_follow on notify.notification (user_id, actor_id)
  where type = 'NEW_FOLLOWER' and read_at is null;
