-- Replay re-scores what was served with another config, so the label view (V130) also
-- carries the features each item was ranked on. The lab's extract reads them from here too.
-- Appending a column is all CREATE OR REPLACE VIEW allows; the existing columns keep their order.
-- Expected indexes: unchanged from V130.
create or replace view feed.served_outcome as
select s.session_id, s.position, s.served_at, s.user_id, s.post_id, s.config_id, s.score, s.exploration,
       coalesce(o.engaged and not o.negative, false) as positive,
       coalesce(o.negative, false)                   as negative,
       o.max_watch_fraction,
       s.features
  from feed.served_item s
  left join lateral (
    select bool_or(e.event_type in ('LIKE', 'COMMENT', 'SHARE', 'COMPLETE', 'PROFILE_TAP', 'GIG_TAP')
                   or (e.event_type = 'WATCH_PROGRESS' and e.value >= 0.75)) as engaged,
           bool_or(e.event_type in ('HIDE', 'REPORT'))                         as negative,
           max(case e.event_type when 'WATCH_PROGRESS' then e.value
                                 when 'COMPLETE' then 1.0::real end)               as max_watch_fraction
      from signals.interaction_event e
     where e.feed_session_id = s.session_id
       and e.user_id = s.user_id
       and e.post_id = s.post_id
       and e.occurred_at >= s.served_at
       and e.occurred_at < s.served_at + interval '1 day'
  ) o on true;
