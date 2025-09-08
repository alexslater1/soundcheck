-- The lab's training table: one row per served item, with the features it was ranked on,
-- its label and the config that served it. The label comes from feed.served_outcome, the one definition of
-- an engaged item; this query adds only the serving config's version and feature schema version.
--
-- One file, two readers: the lab's `extract` (Python, psycopg) and ExtractQueryIT (Java, the label cases).
-- It takes no parameters, so both run it as it is: callers that need a window wrap it,
--   select * from (<this file>) x where served_at >= :since
-- and Postgres pushes the filter down to feed.served_item's served_at (the partition key, first column of
-- the primary key), so a window reads only its own day partitions.
-- Ids are text, so a client without a UUID type reads them as they are.
-- Expected index: served_item's primary key (served_at, session_id, position) for the window; the view's
-- per-item event lookup uses interaction_event_session (feed_session_id) on each pruned partition (V130).
select o.session_id::text            as session_id,
       o.position,
       o.served_at,
       o.user_id::text               as user_id,
       o.post_id::text               as post_id,
       o.config_id::text             as config_id,
       c.version                     as config_version,
       c.feature_schema_version,
       o.score,
       o.exploration,
       o.positive,
       o.negative,
       o.max_watch_fraction,
       o.features
  from feed.served_outcome o
  join feed.ranking_config c on c.id = o.config_id
