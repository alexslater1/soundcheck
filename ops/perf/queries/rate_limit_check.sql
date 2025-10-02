-- rate_limit_check: the Postgres-backed rate limit's row lock (RateLimiter); rolled back.
-- Source: the statement's text as pg_stat_statements and auto_explain record it, with the binds auto_explain logged
-- ("Query Parameters") for one request as perf_u6 (01904b8f-23fc-7e81-99a6-eb4644d73a85) on the small profile (seed 42, loaded
-- by seed-scale at 2025-10-02 18:55Z), captured 2025-10-02T19:16Z from the perf API built at 0acebf9. The ids exist only in
-- that load: after another seed-scale, recapture them.
-- check: select true
begin;
prepare q as
SELECT state FROM shared.rate_limit_bucket WHERE id = $1 FOR UPDATE;
explain (analyze, buffers, format text) execute q('user:01904b8f-23fc-7e81-99a6-eb4644d73a85|1000000/PT1M');
rollback;
