-- V008 — ingestion: a heartbeat on runs, so a run whose process died is recognised after minutes, not hours
-- A RUNNING row whose heartbeat is older than ten minutes is closed as abandoned by the next trigger.

alter table ingestion.ingestion_run add column heartbeat_at timestamptz;
