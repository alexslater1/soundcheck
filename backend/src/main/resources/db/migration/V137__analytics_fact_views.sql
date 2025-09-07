-- The console's analytics read these fact views. Each holds ONE ROW PER FACT (a served
-- item, a post, an uploaded asset, an account), never a daily aggregate: the views resolve the joins and
-- the derived columns, and AnalyticsQueries aggregates over [from, to) on the raw timestamps. A view that
-- grouped by day, filtered afterwards on its day column, could not prune the daily partitions of
-- feed.served_item or signals.interaction_event; a filter on a row-level view's timestamp is pushed down
-- to the base table and can. Days are UK days, bucketed in the queries, not here.
-- Analytics reads other modules' schemas through these views (and the event log directly), never their
-- code (analytics/package-info.java).

-- One row per served For You item (feed.served_outcome: the one definition of "engaged") with
-- the version of the ranking config that served it. The logged features are left out: no chart needs them.
-- Expected index: served_item's partition key (served_at) prunes the period's day partitions; the label
-- lateral (V130) probes each event partition by interaction_event_user (user_id, occurred_at) or
-- interaction_event_session, whichever the planner picks per partition (both on the demo data);
-- ranking_config by its primary key.
create view analytics.serve_fact as
select o.session_id, o.position, o.served_at, o.user_id, o.post_id, o.config_id,
       c.version      as config_version,
       c.activated_at as config_activated_at,
       o.score, o.exploration, o.positive, o.negative, o.max_watch_fraction
  from feed.served_outcome o
  join feed.ranking_config c on c.id = o.config_id;

-- One row per post (content.post) with what it is made of: its clips that have media (a slot still
-- waiting for its upload is not a clip yet), how many are videos and how many photos (media kind IMAGE),
-- and the first clip's kind and length, which is what a viewer sees first in the feed. Posts of every
-- status: a post published and later deleted was still published that day.
-- Expected index: post_published_at (V138) for a period on published_at; per post, post_clip_post
-- (post_id, position) and media_asset's primary key.
create view analytics.post_fact as
select p.id as post_id, p.author_id, p.gig_id, p.status, p.created_at, p.published_at,
       c.clips, c.videos, c.images,
       f.kind        as first_clip_kind,
       f.duration_ms as first_clip_duration_ms
  from content.post p
  cross join lateral (
    select count(*)                                  as clips,
           count(*) filter (where m.kind = 'VIDEO')  as videos,
           count(*) filter (where m.kind = 'IMAGE')  as images
      from content.post_clip pc
      join media.media_asset m on m.id = pc.media_id
     where pc.post_id = p.id
  ) c
  left join lateral (
    select m.kind, m.duration_ms
      from content.post_clip pc
      join media.media_asset m on m.id = pc.media_id
     where pc.post_id = p.id
     order by pc.position, pc.id
     limit 1
  ) f on true;

-- One row per media asset (media.media_asset) with how its processing went:
--   outcome: READY once it has been ready (ready_at is set, even if since DELETED), FAILED for FAILED or
--            REJECTED, null while it is still on its way (or was never uploaded);
--   time_to_ready_ms: ready_at - uploaded_at, null unless both are set.
-- Expected index: media_asset_uploaded_at (V138) for a period on uploaded_at.
create view analytics.media_fact as
select m.id as media_id, m.owner_id, m.kind, m.status, m.duration_ms,
       m.created_at, m.uploaded_at, m.ready_at,
       case when m.ready_at is not null then 'READY'
            when m.status in ('FAILED', 'REJECTED') then 'FAILED'
       end as outcome,
       (extract(epoch from (m.ready_at - m.uploaded_at)) * 1000)::bigint as time_to_ready_ms,
       m.attempts, m.failure_reason
  from media.media_asset m;

-- One row per account (identity.app_user). The email's DOMAIN only, never the address: a synthetic
-- account is one whose email_domain is in soundcheck.admin.synthetic-email-domains (SyntheticAccounts,
-- one definition for the console and analytics); the queries test email_domain = any(:domains).
-- Expected index: app_user_created (V131) for a period on created_at; the primary key for a join on user_id.
create view analytics.user_fact as
select u.id as user_id, u.created_at, u.deleted_at,
       lower(split_part(u.email::text, '@', 2)) as email_domain
  from identity.app_user u;
