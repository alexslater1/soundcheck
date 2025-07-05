-- D25b: a private profile's posts are candidates only for the people its owner follows.
-- Feed reads other modules' data through views in its own schema, so it gets two more facts:
-- whether a post's author is private (a new last column of post_candidate; CREATE OR REPLACE VIEW may
-- only add columns at the end) and the follow edges, to ask "does this author follow the viewer?".
create or replace view feed.post_candidate as
select p.id as post_id, p.author_id, p.gig_id, p.published_at,
       u.profile_visibility = 'PRIVATE' as author_private
  from content.post p
  join media.media_asset m on m.id = p.media_id and m.status = 'READY'
  join identity.app_user u on u.id = p.author_id and u.deleted_at is null
 where p.status = 'PUBLISHED';

-- Who follows whom. Expected index: the user_follow primary key (follower_id, followee_id).
create view feed.follow_edge as
select follower_id, followee_id from social.user_follow;
