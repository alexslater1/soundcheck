-- Each artist or venue follow carries how much the follower wants to hear about it.
--   ALL   new gigs and new posts      POSTS  new posts only
--   GIGS  new gigs only (the default: what every follow did before)      NONE  nothing
-- The follows are listed on a profile's Artists tab, newest first.
alter table social.artist_follow
  add column notify text not null default 'GIGS' check (notify in ('ALL','POSTS','GIGS','NONE'));
alter table social.venue_follow
  add column notify text not null default 'GIGS' check (notify in ('ALL','POSTS','GIGS','NONE'));

-- "Which artists (venues) does this user follow?", newest first with a keyset on (created_at, id).
create index artist_follow_user on social.artist_follow (user_id, created_at desc, artist_id desc);
create index venue_follow_user on social.venue_follow (user_id, created_at desc, venue_id desc);
