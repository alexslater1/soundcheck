-- V020 — catalog: gigs, venues and artists added by users.
-- Ticketmaster lists few small venues and no past shows, so a fan who filmed a pub gig could not tag it.
-- Users can now add the gig (and a missing venue or act); `created_by` marks what came from a user.
-- Their listing is recorded in gig_source with source 'USER' and the gig's own id as external id, so
-- ingestion's resolver can later attach a Ticketmaster/Skiddle listing of the same show to it.
-- A purged account's additions stay (other people's posts and attendance point at them): set null.

alter table catalog.gig    add column created_by uuid references identity.app_user(id) on delete set null;
alter table catalog.venue  add column created_by uuid references identity.app_user(id) on delete set null;
alter table catalog.artist add column created_by uuid references identity.app_user(id) on delete set null;

-- What one user added recently (moderation lists, per-user limits). Partial: almost every row is null.
create index gig_created_by on catalog.gig (created_by, created_at) where created_by is not null;
