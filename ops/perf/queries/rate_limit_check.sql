-- rate_limit_check: the Postgres-backed rate limit's row lock (RateLimiter); rolled back.
-- Source: the statement's text as pg_stat_statements and auto_explain record it, with the binds auto_explain logged
-- ("Query Parameters"), for one request as perf_u205 (01940483-6188-731a-a99b-54ec14467635) on medium (seed 42, loaded
-- by seed-scale at 2026-10-13 02:21Z), logged at 2026-10-13 02:32:31.059 UTC (idle) by the perf API built at 8db165b.
-- The ids exist only in that load: after another seed-scale, recapture them.
-- check: select exists (select 1 from identity.app_user where id = '01940483-6188-731a-a99b-54ec14467635')
begin;
prepare q as
SELECT state FROM shared.rate_limit_bucket WHERE id = $1 FOR UPDATE;
explain (analyze, buffers, format text) execute q('user:01940483-6188-731a-a99b-54ec14467635|1000000/PT1M');
rollback;
