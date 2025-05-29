-- V014 — signals: the interaction event log and what is aggregated from it.

-- Append-only log of what users did with posts. Partitioned by day on the server's receive time:
-- retention is "drop the partition" (90 days), not a slow delete. The partitions
-- signals.interaction_event_pYYYYMMDD are created by the application (at start-up and by the hourly
-- EventPartitionMaintenance job), not here, so that a database migrated long ago still gets today's.
create table signals.interaction_event (
  id              bigint generated always as identity,
  occurred_at     timestamptz not null,               -- server receive time (client_ts kept separately)
  user_id         uuid not null,
  post_id         uuid not null,
  feed_session_id uuid,
  surface         text not null check (surface in ('FOR_YOU','FOLLOWING','PROFILE','GIG','ARTIST','POST','SEARCH')),
  position        int,
  event_type      text not null check (event_type in ('IMPRESSION','VIEW_START','WATCH_PROGRESS','COMPLETE','LIKE','UNLIKE','COMMENT','SHARE','PROFILE_TAP','GIG_TAP','HIDE','REPORT')),
  value           real,                                -- watch fraction for WATCH_PROGRESS (0.25/0.5/0.75)
  client_ts       timestamptz,
  platform        text check (platform in ('android','web')),
  primary key (occurred_at, id)                        -- a partitioned table's key must contain the partition key
) partition by range (occurred_at);
create index interaction_event_post on signals.interaction_event (post_id, occurred_at);      -- per-post reads
create index interaction_event_user on signals.interaction_event (user_id, occurred_at);      -- "seen in the last 3 days"
create index interaction_event_session on signals.interaction_event (feed_session_id) where feed_session_id is not null;  -- outcomes of a served session (lab)

create table signals.post_stats (                     -- all-time counters for ranking; updated by the rollup job
  post_id      uuid primary key references content.post(id) on delete cascade,
  impressions  bigint not null default 0,
  views        bigint not null default 0,
  completes    bigint not null default 0,
  likes        bigint not null default 0,
  comments     bigint not null default 0,
  shares       bigint not null default 0,
  hides        bigint not null default 0,
  watch_fraction_sum double precision not null default 0,
  updated_at   timestamptz not null default now()
);

create table signals.post_stats_hourly (               -- for trending windows
  post_id     uuid not null,
  hour        timestamptz not null,                   -- date_trunc('hour')
  impressions int not null default 0,
  engagements int not null default 0,                 -- likes + comments + shares + completes
  primary key (hour, post_id)
);
create index post_stats_hourly_post on signals.post_stats_hourly (post_id, hour);

-- How far each rollup has read the event log.
create table signals.rollup_watermark (
  name             text primary key,
  last_event_id    bigint not null,
  last_occurred_at timestamptz not null
);

create table signals.user_artist_affinity (
  user_id uuid not null, artist_id uuid not null,
  score   real not null check (score between 0 and 1),
  updated_at timestamptz not null default now(),
  primary key (user_id, artist_id)
);
create table signals.user_author_affinity (
  user_id uuid not null, author_id uuid not null,
  score   real not null check (score between 0 and 1),
  updated_at timestamptz not null default now(),
  primary key (user_id, author_id)
);
create table signals.user_genre_affinity (            -- implicit, from engagement; blended with explicit preferences at read time
  user_id uuid not null, genre_id smallint not null,
  score   real not null check (score between 0 and 1),
  updated_at timestamptz not null default now(),
  primary key (user_id, genre_id)
);
