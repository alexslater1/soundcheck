-- V016 — signals: the global engagement prior used by ranking feature 8.
-- The global mean rate is cached hourly. A one-row table lets the
-- scheduler compute it once and every API instance read the same numbers.
create table signals.global_prior (
  id         boolean primary key default true check (id),   -- exactly one row
  mean_rate  double precision not null,                     -- m: (likes + 2·comments + shares + completes) / impressions, over all posts
  p99_rate   double precision not null,                     -- 99th percentile of the smoothed per-post rate; feature 8 is scaled by it
  posts      int not null,                                   -- how many posts had impressions when it was computed
  updated_at timestamptz not null
);
