-- V154 — ingestion: stop a run from the console.
--
-- A QUEUED run is closed at once (status STOPPED). A RUNNING run only gets stop_requested_at: the process
-- running it reads the flag with its next heartbeat (at most every 30 seconds), stops, and closes the row
-- as STOPPED itself. Until then the row stays RUNNING, so ingestion_run_one_active still keeps a second run
-- of the source from starting while the first is still writing.

alter table ingestion.ingestion_run drop constraint ingestion_run_status_check;
alter table ingestion.ingestion_run add constraint ingestion_run_status_check
  check (status in ('QUEUED','RUNNING','SUCCEEDED','PARTIAL','FAILED','STOPPED'));

-- When an operator asked the run to stop, and who (identity.app_user.id; no foreign key across modules).
alter table ingestion.ingestion_run add column stop_requested_at timestamptz;
alter table ingestion.ingestion_run add column stop_requested_by uuid;
