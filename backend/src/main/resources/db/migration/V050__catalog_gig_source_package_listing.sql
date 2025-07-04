-- A package listing (VIP, "Venue Premium Tickets", hospitality…) is a property of the
-- listing, not of the gig. Ingestion used to recognise one by the gig's stored title; now that a gig which
-- only a package listing announces keeps its show's plain title, the listing carries the flag instead.
alter table catalog.gig_source
    add column package_listing boolean not null default false;

-- Backfill: the primary listings of gigs whose stored title is still a package title (the same words as
-- EntityResolver.PACKAGE). Their next run strips the title and refreshes the flag.
update catalog.gig_source s
   set package_listing = true
  from catalog.gig g
 where g.id = s.gig_id
   and s.primary_listing
   and g.title ~* '\y(vip|premium|packages?|hospitality|upgrades?|platinum|meet\s*(&|and|n)?\s*greet|early entry|priority entry)\y';
