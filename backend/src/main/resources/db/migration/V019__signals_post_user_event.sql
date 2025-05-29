-- V019 — signals: who has done what to a post, once.
-- The post counters were sums of raw client events, so one account could send a hundred SHARE events
-- and make a post "trending", or a hundred IMPRESSION events and push a rival's post out of the fresh
-- pool. The rollup now counts a (post, user, kind) once: a row here is the proof that it was counted.
-- kind is the event type, with WATCH_PROGRESS split by mark (WATCH_25, WATCH_50, WATCH_75).
create table signals.post_user_event (
  post_id  uuid not null references content.post(id) on delete cascade,
  user_id  uuid not null,
  kind     text not null check (kind in ('IMPRESSION','VIEW_START','WATCH_25','WATCH_50','WATCH_75','COMPLETE','LIKE','COMMENT','SHARE','HIDE')),
  active   boolean not null default true,     -- false for a LIKE that was taken back; a later like turns it on again
  first_at timestamptz not null,
  primary key (post_id, user_id, kind)
);
create index post_user_event_user on signals.post_user_event (user_id);   -- account purge
