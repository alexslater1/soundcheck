-- rollup_end_of_batch: where the post-stats rollup's next batch of 50,000 events ends (EventLog.endOfBatch).
-- Written by hand in jOOQ's shape from EventLog.java (not captured from a log: the perf profile runs no scheduler,
-- so the rollup never ran under load). The watermark is the perf database's own `post_stats` row after the
-- medium load (2026-10-13 01:59:59.699504Z, id 10000000).
-- The question: does the plain `occurred_at >=` bound beside the row
-- comparison let PostgreSQL prune the daily partitions before the watermark?
-- check: select exists (select 1 from signals.rollup_watermark where name = 'post_stats' and last_event_id = 10000000 and last_occurred_at = '2026-10-13 01:59:59.699504+00')
begin;
prepare q as
select "batch"."occurred_at", "batch"."id" from (select "signals"."interaction_event"."occurred_at", "signals"."interaction_event"."id" from "signals"."interaction_event" where ("signals"."interaction_event"."occurred_at" >= cast($1 as timestamp(6) with time zone) and ("signals"."interaction_event"."occurred_at", "signals"."interaction_event"."id") > (cast($2 as timestamp(6) with time zone), cast($3 as bigint)) and "signals"."interaction_event"."occurred_at" <= now() - $4 * interval '1 millisecond') order by "signals"."interaction_event"."occurred_at", "signals"."interaction_event"."id" limit $5) as "batch" order by "batch"."occurred_at" desc, "batch"."id" desc limit 1;
explain (analyze, buffers, format text) execute q('2026-10-13 01:59:59.699504+00', '2026-10-13 01:59:59.699504+00', 10000000, 5000, 50000);
rollback;
