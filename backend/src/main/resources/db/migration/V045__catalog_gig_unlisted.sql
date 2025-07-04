-- A gig only a venue's own site listed, which that site has stopped showing for
-- two full reads in a row, is marked UNLISTED: kept (people may have marked it, its page still opens)
-- but left out of every list that helps someone find gigs. It is SCHEDULED again if the site lists it again.
alter table catalog.gig drop constraint gig_status_check;
alter table catalog.gig add constraint gig_status_check
  check (status in ('SCHEDULED', 'POSTPONED', 'RESCHEDULED', 'CANCELLED', 'UNLISTED'));
