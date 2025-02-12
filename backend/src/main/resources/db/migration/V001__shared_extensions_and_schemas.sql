-- V001 — extensions, one schema per module, and the shared tables.
-- Forward-only. Needs a superuser (or extension-privileged role) the first time, for the extensions.

-- ===== extensions =====
create extension if not exists postgis;
create extension if not exists pg_trgm;
create extension if not exists citext;
create extension if not exists unaccent;
create extension if not exists btree_gist;
create extension if not exists pg_stat_statements; -- needs shared_preload_libraries (compose.yaml / RDS parameter group)

-- ===== schemas (one per module) =====
create schema if not exists shared;
create schema if not exists identity;
create schema if not exists social;
create schema if not exists catalog;
create schema if not exists ingestion;
create schema if not exists media;
create schema if not exists content;
create schema if not exists signals;
create schema if not exists feed;
create schema if not exists notify;
create schema if not exists analytics;

-- Immutable wrapper so unaccent can be used in indexes (name normalisation, search).
create or replace function shared.f_unaccent(text) returns text
  language sql immutable parallel safe strict as $$ select public.unaccent('public.unaccent', $1) $$;

-- ===== shared =====
-- Idempotency-Key replay store: rows older than 24 h are deleted hourly by a scheduled job.
create table shared.idempotency_key (
  key             text        not null,
  user_id         uuid        not null,
  request_hash    bytea       not null,          -- sha256 of method+path+body
  response_status int,
  response_body   jsonb,
  created_at      timestamptz not null default now(),
  primary key (user_id, key)
);
-- Expected index for the hourly purge: created_at.
create index idempotency_key_created_at on shared.idempotency_key (created_at);

-- ShedLock (single execution of @Scheduled jobs across API instances).
create table shared.shedlock (
  name       varchar(64)  primary key,
  lock_until timestamptz  not null,
  locked_at  timestamptz  not null,
  locked_by  varchar(255) not null
);

-- Spring Modulith Event Publication Registry = the transactional outbox.
-- DDL copied from spring-modulith-events-jdbc 1.3.2 (schema-postgresql.sql) so Flyway owns it;
-- schema initialisation is disabled in application.yaml.
create table shared.event_publication (
  id                     uuid        not null,
  listener_id            text        not null,
  event_type             text        not null,
  serialized_event       text        not null,
  publication_date       timestamptz not null,
  completion_date        timestamptz,
  primary key (id)
);
create index event_publication_serialized_event_hash_idx on shared.event_publication using hash (serialized_event);
create index event_publication_by_completion_date_idx on shared.event_publication (completion_date);

-- Completed publications are moved here (completion-mode ARCHIVE) so the hot table stays small.
create table shared.event_publication_archive (
  id                     uuid        not null,
  listener_id            text        not null,
  event_type             text        not null,
  serialized_event       text        not null,
  publication_date       timestamptz not null,
  completion_date        timestamptz,
  primary key (id)
);
create index event_publication_archive_serialized_event_hash_idx on shared.event_publication_archive using hash (serialized_event);
create index event_publication_archive_by_completion_date_idx on shared.event_publication_archive (completion_date);
