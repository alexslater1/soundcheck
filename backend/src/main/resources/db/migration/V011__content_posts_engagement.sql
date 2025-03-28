-- V011 — content: posts, artist tags, likes, comments, hides, reports.

create table content.post (
  id            uuid primary key,
  author_id     uuid not null references identity.app_user(id),
  media_id      uuid not null unique references media.media_asset(id),     -- one post per clip
  gig_id        uuid references catalog.gig(id),
  caption       text check (char_length(caption) <= 2200),
  status        text not null check (status in ('PENDING_MEDIA','PUBLISHED','FAILED','HIDDEN','DELETED')),
  like_count    int not null default 0,                                    -- changed in the same transaction as post_like
  comment_count int not null default 0,
  search_tsv    tsvector generated always as (to_tsvector('simple', coalesce(caption,''))) stored,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  published_at  timestamptz
);
create index post_author_pub on content.post (author_id, published_at desc, id) where status = 'PUBLISHED';  -- profile grid, Following feed
create index post_gig_pub on content.post (gig_id, published_at desc) where status = 'PUBLISHED';            -- a gig's clips
create index post_pub on content.post (published_at desc, id) where status = 'PUBLISHED';                    -- recency candidates
create index post_search on content.post using gin (search_tsv);
create index post_author_all on content.post (author_id, created_at desc);                                   -- the author's own grid (every status), purge

create table content.post_artist (
  post_id   uuid not null references content.post(id) on delete cascade,
  artist_id uuid not null references catalog.artist(id),
  primary key (post_id, artist_id)
);
create index post_artist_artist on content.post_artist (artist_id, post_id);                                 -- an artist's clips

create table content.post_like (
  user_id    uuid not null references identity.app_user(id) on delete cascade,
  post_id    uuid not null references content.post(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (post_id, user_id)
);
create index post_like_user on content.post_like (user_id, created_at desc);                                 -- likedByMe lookups, purge

create table content.comment (
  id         uuid primary key,
  post_id    uuid not null references content.post(id) on delete cascade,
  author_id  uuid not null references identity.app_user(id),
  body       text not null check (char_length(body) between 1 and 500),
  created_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index comment_post on content.comment (post_id, created_at, id);                                      -- a post's comments, oldest first
create index comment_author on content.comment (author_id);                                                  -- purge

create table content.post_hide (                      -- "show me less"; excluded from that user's feeds
  user_id    uuid not null references identity.app_user(id) on delete cascade,
  post_id    uuid not null references content.post(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, post_id)
);

create table content.report (
  id          uuid primary key,
  reporter_id uuid not null references identity.app_user(id),
  post_id     uuid references content.post(id) on delete cascade,
  comment_id  uuid references content.comment(id) on delete cascade,
  reason      text not null check (reason in ('SPAM','ABUSE','NUDITY','VIOLENCE','COPYRIGHT','OTHER')),
  details     text check (char_length(details) <= 500),
  status      text not null default 'OPEN' check (status in ('OPEN','ACTIONED','DISMISSED')),
  created_at  timestamptz not null default now(),
  resolved_at timestamptz, resolved_by uuid,
  check (num_nonnulls(post_id, comment_id) = 1)
);
create index report_open on content.report (created_at) where status = 'OPEN';                               -- moderation queue
create unique index report_once_post on content.report (reporter_id, post_id) where post_id is not null;     -- one report per user and target
create unique index report_once_comment on content.report (reporter_id, comment_id) where comment_id is not null;

-- identity.app_user.avatar_media_id was created in V002 without its foreign key ("added with the media
-- schema"); it is added here, with the media schema.
alter table identity.app_user
  add constraint app_user_avatar_media_fk foreign key (avatar_media_id) references media.media_asset(id) on delete set null;
