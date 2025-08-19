-- The one definition of an engaged item. One row per served item (feed.served_item)
-- with what the user did with that post in the same feed session:
--   positive: any of LIKE, COMMENT, SHARE, COMPLETE, PROFILE_TAP, GIG_TAP, or WATCH_PROGRESS >= 0.75;
--   negative: HIDE or REPORT, which wins regardless (a hidden item is never positive);
--   max_watch_fraction: the furthest WATCH_PROGRESS mark, 1.0 after a COMPLETE, null if never watched.
-- Replay metrics, analytics and the lab's extract all read this view; nothing else defines "engaged".
--
-- Events are matched on (feed_session_id, user_id, post_id): an event's session id is not checked when
-- it is received, so the user and post must match too. Only events received after the serve and within a
-- day of it count (a session expires after 30 minutes); the bounds let the planner prune the event log's
-- day partitions. Expected indexes: interaction_event_session (feed_session_id) on each pruned partition,
-- and served_item's own partition key (served_at) when the caller filters by period.
-- Feed reads signals' table through a view in its own schema.
create view feed.served_outcome as
select s.session_id, s.position, s.served_at, s.user_id, s.post_id, s.config_id, s.score, s.exploration,
       coalesce(o.engaged and not o.negative, false) as positive,
       coalesce(o.negative, false)                   as negative,
       o.max_watch_fraction
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
