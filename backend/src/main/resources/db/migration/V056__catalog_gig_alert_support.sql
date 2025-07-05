-- New-gig alerts.
-- A saved search alerts by default; the fan can switch it off per search.
alter table catalog.saved_gig_filter add column alerts boolean not null default true;
-- The alert pass reads gigs in creation order after a cursor (catalog.NewGigs.createdAfter).
create index gig_created on catalog.gig (created_at, id);
