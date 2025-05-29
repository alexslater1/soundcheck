-- V017 — feed: the read contract of the ranking pipeline.
--
-- Candidate generation is one SQL statement over posts, their tags, gig locations and engagement
-- counters, and those tables belong to other modules. Instead of reaching into them, feed
-- reads these views in its own schema: each exposes only the columns ranking needs, under names feed
-- controls. A change to an underlying column that a view uses fails that module's migration, so the
-- dependency cannot break silently. Views are inlined by the planner: the underlying indexes are used.

-- A post that may be served: published, its clip playable, its author's account live.
create view feed.post_candidate as
select p.id as post_id, p.author_id, p.gig_id, p.published_at
  from content.post p
  join media.media_asset m on m.id = p.media_id and m.status = 'READY'
  join identity.app_user u on u.id = p.author_id and u.deleted_at is null
 where p.status = 'PUBLISHED';

-- The artists tagged in a post.
create view feed.post_tag as
select post_id, artist_id from content.post_artist;

-- An artist's genres with their weights.
create view feed.artist_genre as
select artist_id, genre_id, weight from catalog.artist_genre;

-- Where a gig takes place.
create view feed.gig_place as
select id as gig_id, location from catalog.gig;

-- A post's all-time counters and its hourly engagement (trending).
create view feed.post_stat as
select post_id, impressions, likes, comments, shares, completes from signals.post_stats;
create view feed.post_stat_hourly as
select post_id, hour, impressions, engagements from signals.post_stats_hourly;

-- Who liked or commented on a post (social proof).
create view feed.post_engager as
select post_id, user_id from content.post_like
union all
select post_id, author_id as user_id from content.comment where deleted_at is null;
