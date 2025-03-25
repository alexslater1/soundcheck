-- V009 — media: upload sessions / assets and one transcode_job row per processing attempt.

create table media.media_asset (
  id               uuid primary key,
  owner_id         uuid not null references identity.app_user(id),
  kind             text not null check (kind in ('VIDEO','IMAGE')),
  status           text not null check (status in ('AWAITING_UPLOAD','UPLOADED','PROCESSING','READY','REJECTED','FAILED','DELETED')),
  content_type     text not null,
  declared_bytes   bigint not null,
  actual_bytes     bigint,
  original_key     text not null,                    -- uploads/{ownerId}/{mediaId}/original (never a user-supplied name)
  upload_strategy  text not null check (upload_strategy in ('SINGLE','MULTIPART')),
  multipart_upload_id text,
  part_size        int,
  client_meta      jsonb not null default '{}',      -- {originalBytes, compressedBytes, compressMs, platform, appVersion}
  probe            jsonb,                            -- ffprobe summary
  duration_ms      int,
  width            int,
  height           int,
  output_prefix    text,                             -- media/{mediaId}/v{n}/
  renditions       jsonb,                            -- [{name:'720p', width, height, bitrate, playlist}]
  poster_key       text,
  thumb_key        text,
  blurhash         text,
  attempts         int not null default 0,
  claimed_by       text,                             -- worker lease
  claimed_at       timestamptz,
  failure_reason   text,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  uploaded_at      timestamptz,
  ready_at         timestamptz
);
create index media_asset_owner on media.media_asset (owner_id, created_at desc);              -- "my uploads", profile grid
create index media_asset_status on media.media_asset (status, updated_at)
  where status in ('UPLOADED','PROCESSING','FAILED');                                         -- pipeline monitor, stuck-job sweeps

create table media.transcode_job (                   -- one row per attempt; powers the pipeline console
  id            uuid primary key,
  media_id      uuid not null references media.media_asset(id) on delete cascade,
  attempt       int not null,
  worker_id     text not null,
  status        text not null check (status in ('RUNNING','SUCCEEDED','FAILED','REJECTED','RELEASED')),
  queue_wait_ms int,                                 -- claim time minus the message's SentTimestamp
  download_ms   int,
  probe_ms      int,
  transcode_ms  int,
  upload_ms     int,
  total_ms      int,
  error         text,
  started_at    timestamptz not null,
  finished_at   timestamptz
);
create index transcode_job_media on media.transcode_job (media_id, attempt);
