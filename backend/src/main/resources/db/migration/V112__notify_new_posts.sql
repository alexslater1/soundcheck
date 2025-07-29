-- Followers whose level for an artist or a venue is POSTS or ALL hear about its new posts.
-- One entry per (user, artist or venue) per hour at most: a second post within the hour adds to the
-- entry's count ("3 new posts from KEO") instead of adding an entry.
alter table notify.notification
  add column subject_id   uuid,     -- the artist or venue
  add column subject_name text,     -- its name then (notifications cannot read the catalog)
  add column post_count   int,      -- how many new posts the entry stands for
  add column rollup_key   text;     -- subject id and clock hour of the first post; see the unique index
alter table notify.notification add check (
  (type in ('NEW_POSTS_ARTIST','NEW_POSTS_VENUE'))
    = (subject_id is not null and subject_name is not null and post_count is not null and rollup_key is not null));

alter table notify.notification drop constraint notification_type_check;
alter table notify.notification add constraint notification_type_check
  check (type in ('NEW_FOLLOWER','POST_LIKED','POST_COMMENTED','POST_READY','POST_FAILED','NEW_GIGS',
                  'FOLLOW_REQUEST','FOLLOW_ACCEPTED','POST_TAGGED','NEW_POSTS_ARTIST','NEW_POSTS_VENUE'));

-- "Is there an entry for this subject from the last hour?" (the roll-up).
create index notification_new_posts_recent on notify.notification (user_id, type, subject_id, created_at desc)
  where subject_id is not null;
-- Two posts published at the same moment cannot both start an entry for the same subject and hour.
create unique index notification_new_posts_once on notify.notification (user_id, type, rollup_key)
  where rollup_key is not null;
