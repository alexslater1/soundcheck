-- V015 — feed: ranking configs, feed sessions and the serve-time feature log.

create table feed.ranking_config (
  id           uuid primary key,
  version      int not null unique,                   -- monotonically increasing, human-friendly
  status       text not null check (status in ('DRAFT','ACTIVE','ARCHIVED')),
  feature_schema_version int not null,                -- contracts/ranking/feature-schema.json version
  weights      jsonb not null,                        -- {"bias":-2.0,"follows_author":1.6,…}
  params       jsonb not null,                        -- tuning parameters (RankingConfig.Params)
  source       text not null check (source in ('SEED','MANUAL','LAB')),
  metrics      jsonb,                                 -- offline/replay evaluation results
  notes        text,
  created_by   uuid,
  created_at   timestamptz not null default now(),
  activated_at timestamptz
);
-- At most one ACTIVE config: every ACTIVE row has the same indexed value, so a second one collides.
create unique index ranking_config_one_active on feed.ranking_config ((status)) where status = 'ACTIVE';

-- A built feed: the ranked list a user pages through. Cache-like, so UNLOGGED (no WAL; emptied by a
-- crash, after which clients get 410 and start a new session). Redis is the scale path.
create unlogged table feed.feed_session (
  id          uuid primary key,
  user_id     uuid not null,
  config_id   uuid not null,
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null,                   -- created_at + 30 min
  items       jsonb not null                          -- [{p:postId, s:score, f:[features…], x:isExploration, src:[candidateSources]}]
);
create index feed_session_expires on feed.feed_session (expires_at);   -- the cleanup job

-- What was shown, with the exact features it was scored on: the training data. Partitioned by
-- day, 30-day retention; partitions feed.served_item_pYYYYMMDD are created by the application like the
-- event log's (V014).
create table feed.served_item (
  session_id  uuid not null,
  position    int  not null,
  served_at   timestamptz not null,
  user_id     uuid not null,
  post_id     uuid not null,
  config_id   uuid not null,
  score       real not null,
  features    real[] not null,                        -- order = feature_schema_version
  exploration boolean not null,
  primary key (served_at, session_id, position)
) partition by range (served_at);

-- Ranking config v1: the hand-tuned weights and default parameters, ACTIVE from the start.
insert into feed.ranking_config (id, version, status, feature_schema_version, weights, params, source, notes, activated_at)
values (
  '00000000-0000-7000-8000-000000000001', 1, 'ACTIVE', 1,
  '{"bias": -2.0, "follows_author": 1.6, "follows_artist": 1.2, "artist_affinity": 1.0, "author_affinity": 0.8,
    "genre_similarity": 1.0, "gig_relation": 1.4, "proximity": 0.6, "recency": 1.5, "engagement_quality": 1.8,
    "social_proof": 0.7, "seen": -2.5, "position_prior": 0.0}'::jsonb,
  '{"halfLifeHours": 24, "priorStrength": 20, "diversityLambda": 0.8, "explorationRate": 0.1,
    "sessionSize": 200, "pageSize": 10,
    "candidateCaps": {"FOLLOWING": 300, "ARTIST": 300, "GIG": 200, "TASTE": 300, "LOCAL": 200, "TRENDING": 200, "FRESH": 100},
    "localRadiusKm": 50, "seenWindowDays": 3}'::jsonb,
  'SEED', 'Hand-tuned starting weights.', now()
);
