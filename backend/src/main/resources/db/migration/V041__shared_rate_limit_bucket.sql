-- Bucket4j with a PostgreSQL-backed proxy manager: rate-limit buckets live in the
-- database, so every API process draws on one budget per key instead of each counting its own.
-- `id` is the limit's key with its capacity and period ("auth:ip:203.0.113.9|10/PT1M"), `state` is
-- Bucket4j's serialized bucket, written only by Bucket4j (SELECT … FOR UPDATE, then UPDATE).
create table shared.rate_limit_bucket (
    id         text primary key,
    state      bytea,
    updated_at timestamptz not null default now()
);

-- Bucket4j's UPDATE sets only `state`; the trigger keeps `updated_at` current so idle rows can be found.
create function shared.touch_rate_limit_bucket() returns trigger
    language plpgsql as
$$
begin
    new.updated_at := now();
    return new;
end
$$;

create trigger rate_limit_bucket_touch
    before update on shared.rate_limit_bucket
    for each row
execute function shared.touch_rate_limit_bucket();

-- RateLimitBucketCleanup deletes rows idle for longer than any limit's period (a full bucket).
create index rate_limit_bucket_updated on shared.rate_limit_bucket (updated_at);
