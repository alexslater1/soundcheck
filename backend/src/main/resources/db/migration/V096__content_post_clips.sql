-- A post is one fan's clips from one gig and day, not one clip.
--
-- Expand: content.post_clip holds a post's clips (one row per slot: a clip the app has announced,
-- with its media once the upload is complete), content.post_person the people tagged in it, and
-- post.day the UK day its clips are from. Migrate: every post's clip becomes its first slot, then the
-- posts one author made from one gig are merged into the oldest. post.media_id is no longer read;
-- it is made nullable here and dropped in a later release (contract), so the previous build still runs.

alter table content.post add column day date;   -- UK calendar day of the clips; null for a post not from a gig

update content.post p
   set day = (g.starts_at at time zone 'Europe/London')::date
  from catalog.gig g
 where g.id = p.gig_id;

create table content.post_clip (
  id               uuid primary key,
  post_id          uuid not null references content.post(id) on delete cascade,
  owner_id         uuid not null references identity.app_user(id),           -- the author, or a collaborator
  position         int not null,                                             -- order in the post (gaps allowed)
  artist_id        uuid references catalog.artist(id),                       -- the act on stage; null = no act
  media_id         uuid unique references media.media_asset(id),             -- null until the upload is complete
  preview_media_id uuid references media.media_asset(id) on delete set null, -- a frame, shown blurred meanwhile
  created_at       timestamptz not null default now()
);
create index post_clip_post on content.post_clip (post_id, position);                  -- a post's clips, in order
create index post_clip_owner on content.post_clip (owner_id, post_id);                 -- a collaborator's clips, purge
create index post_clip_waiting on content.post_clip (created_at) where media_id is null; -- slots that never got a clip

create table content.post_person (
  post_id    uuid not null references content.post(id) on delete cascade,
  user_id    uuid not null references identity.app_user(id) on delete cascade,
  status     text not null check (status in ('TAGGED', 'ON_PROFILE', 'REMOVED')),  -- REMOVED: they took the tag off
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (post_id, user_id)
);
create index post_person_profile on content.post_person (user_id, post_id) where status = 'ON_PROFILE';  -- profile grid, Following

-- Every post's clip becomes its first slot (deleted posts too: a clip stays in at most one post). The
-- act is the post's only tagged artist; a post that tagged several (the whole bill, by default) has no act.
insert into content.post_clip (id, post_id, owner_id, position, artist_id, media_id, created_at)
select gen_random_uuid(), p.id, p.author_id, 0,
       (select min(pa.artist_id::text)::uuid from content.post_artist pa where pa.post_id = p.id
         having count(*) = 1),
       p.media_id, p.created_at
  from content.post p;

-- Merge: the live or pending posts one author made from one gig and day, into the oldest.
create temporary table post_merge on commit drop as
select id as from_id,
       first_value(id) over w as into_id,
       row_number() over w - 1 as position
  from content.post
 where gig_id is not null and status in ('PUBLISHED', 'PENDING_MEDIA')
window w as (partition by author_id, gig_id, day order by created_at, id);

update content.post_clip c
   set post_id = m.into_id, position = m.position
  from post_merge m
 where c.post_id = m.from_id and m.from_id <> m.into_id;

insert into content.post_like (user_id, post_id, created_at)
select l.user_id, m.into_id, min(l.created_at)
  from content.post_like l join post_merge m on m.from_id = l.post_id
 where m.from_id <> m.into_id
 group by l.user_id, m.into_id
on conflict (post_id, user_id) do nothing;

update content.comment c
   set post_id = m.into_id
  from post_merge m
 where c.post_id = m.from_id and m.from_id <> m.into_id;

insert into content.post_hide (user_id, post_id, created_at)
select h.user_id, m.into_id, min(h.created_at)
  from content.post_hide h join post_merge m on m.from_id = h.post_id
 where m.from_id <> m.into_id
 group by h.user_id, m.into_id
on conflict (user_id, post_id) do nothing;

insert into content.post_artist (post_id, artist_id)
select distinct m.into_id, pa.artist_id
  from content.post_artist pa join post_merge m on m.from_id = pa.post_id
 where m.from_id <> m.into_id
on conflict do nothing;

-- The kept post: the first caption anyone wrote, live if any of them was, published when the first was.
update content.post p
   set caption = coalesce(p.caption, (
         select o.caption from content.post o join post_merge m on m.from_id = o.id
          where m.into_id = p.id and o.caption is not null
          order by o.created_at, o.id limit 1)),
       status = case when exists (
         select 1 from content.post o join post_merge m on m.from_id = o.id
          where m.into_id = p.id and o.status = 'PUBLISHED') then 'PUBLISHED' else p.status end,
       published_at = (
         select min(o.published_at) from content.post o join post_merge m on m.from_id = o.id
          where m.into_id = p.id),
       updated_at = now()
 where p.id in (select into_id from post_merge where from_id <> into_id);

-- The merged-away posts are deleted as a user's delete would (signals, served items and notifications
-- that point at them behave as for any deleted post). Their likes stay with them, unseen.
update content.post p
   set status = 'DELETED', updated_at = now()
  from post_merge m
 where p.id = m.from_id and m.from_id <> m.into_id;

update content.post p
   set like_count = (select count(*) from content.post_like l where l.post_id = p.id),
       comment_count = (select count(*) from content.comment c where c.post_id = p.id and c.deleted_at is null)
 where p.id in (select into_id from post_merge where from_id <> into_id);

-- One post per author, gig and day while it is live or pending: new clips join it.
create unique index post_author_gig_day on content.post (author_id, gig_id, day)
  where gig_id is not null and status in ('PUBLISHED', 'PENDING_MEDIA');

alter table content.post alter column media_id drop not null;

-- The feed's candidates: a published post with at least one playable clip. Columns unchanged (V061).
-- Expected index: post_clip_post per post, then the media_asset primary key.
create or replace view feed.post_candidate as
select p.id as post_id, p.author_id, p.gig_id, p.published_at,
       u.profile_visibility = 'PRIVATE' as author_private
  from content.post p
  join identity.app_user u on u.id = p.author_id and u.deleted_at is null
 where p.status = 'PUBLISHED'
   and exists (select 1
                 from content.post_clip c
                 join media.media_asset m on m.id = c.media_id and m.status = 'READY'
                where c.post_id = p.id);
