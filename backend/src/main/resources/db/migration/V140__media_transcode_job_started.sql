-- V140 — media: the pipeline console reads transcode attempts by start time: the 50 most recent,
-- and the stage percentiles, failures and daily transcode times over a date range. Without this index
-- each refresh would scan the whole attempt history.
create index transcode_job_started on media.transcode_job (started_at desc);
