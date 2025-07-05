-- Fans can follow a venue, as they follow an artist (V006), so a new gig there
-- reaches them as an alert. Social owns follows; catalog asks through catalog.VenueFollows.
create table social.venue_follow (
  user_id    uuid not null references identity.app_user(id) on delete cascade,   -- account purge removes them
  venue_id   uuid not null references catalog.venue(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, venue_id)
);
-- "Who follows these venues?" for the alert pass and the follower count.
create index venue_follow_venue on social.venue_follow (venue_id);
