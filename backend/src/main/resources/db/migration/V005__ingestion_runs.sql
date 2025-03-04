-- V005 — ingestion: runs and replayable raw payloads.

create table ingestion.ingestion_run (
  id          uuid primary key,
  source      text not null,
  trigger     text not null check (trigger in ('SCHEDULE','CONSOLE','CLI','TEST')),
  status      text not null check (status in ('RUNNING','SUCCEEDED','PARTIAL','FAILED')),
  started_at  timestamptz not null,
  finished_at timestamptz,
  stats       jsonb not null default '{}',           -- fetched, created, updated, unchanged, merged, skipped, errors, apiCalls
  issues      jsonb not null default '[]',           -- data-quality issues sample (max 200)
  error       text
);
-- At most one RUNNING run per source: a concurrent trigger fails on this index instead of racing.
create unique index ingestion_run_one_running on ingestion.ingestion_run (source) where status = 'RUNNING';
create index ingestion_run_started on ingestion.ingestion_run (started_at desc);

create table ingestion.raw_payload (                  -- replayable raw pages; pruned after 14 days
  id         uuid primary key,
  run_id     uuid not null references ingestion.ingestion_run(id) on delete cascade,
  source     text not null,
  request    text not null,                          -- URL/query without secrets
  body       jsonb not null,
  fetched_at timestamptz not null default now()
);
create index raw_payload_run on ingestion.raw_payload (run_id);
create index raw_payload_fetched on ingestion.raw_payload (fetched_at);   -- 14-day prune job
