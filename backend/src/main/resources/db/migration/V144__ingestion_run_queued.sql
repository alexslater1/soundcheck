-- V144 — ingestion: "Run now" from the console. The API process only queues a run (a
-- QUEUED row); a process with the scheduler role claims it (QUEUED → RUNNING) and runs it.
--
-- The new partial unique index enforces one queued-or-running run per source: a second "Run now", or the
-- 05:10 schedule meeting a queued source, fails on it with 409 INGESTION_ALREADY_RUNNING instead of
-- queueing or running the same source twice. It covers everything ingestion_run_one_running did, so that
-- index is dropped in the same migration. Replacing an index in place is not an expand → migrate →
-- contract sequence: the table holds a few runs a day, so building the index takes milliseconds, and
-- both steps share this migration's transaction, so there is no moment without the guard.

alter table ingestion.ingestion_run drop constraint ingestion_run_status_check;
alter table ingestion.ingestion_run add constraint ingestion_run_status_check
  check (status in ('QUEUED','RUNNING','SUCCEEDED','PARTIAL','FAILED'));

-- Who pressed "Run now" (identity.app_user.id; no foreign key across modules). Null for other triggers.
alter table ingestion.ingestion_run add column requested_by uuid;
-- When the run was queued. Null for runs that started at once (schedule, CLI). started_at of a QUEUED row
-- is its queueing time too, and becomes the real start when a scheduler claims it.
alter table ingestion.ingestion_run add column queued_at timestamptz;

create unique index ingestion_run_one_active on ingestion.ingestion_run (source)
  where status in ('QUEUED','RUNNING');
drop index ingestion.ingestion_run_one_running;
